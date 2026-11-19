package dev.yajatghule.aipoisoner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer

/**
 * "Protect a photo": sends the photo to the backend on the person's own computer, follows the job, and saves the
 * result to Pictures/AI Poisoner. Runs on its own thread and publishes to [ProtectState], so rotating, leaving the
 * app or coming back doesn't lose a running job. Only one photo at a time: a new one cancels the old.
 */
object Protect {
    private const val THUMB_SIDE = 640
    private const val POLL_MS = 1000L
    /** How many failed status checks in a row (about 1 per second) before we give up on the computer. */
    private const val MAX_LOST_POLLS = 20
    const val FOLDER = "AI Poisoner"

    private class Job { @Volatile var cancelled = false }

    private var active: Job? = null

    // ---------------- test connection ----------------

    fun testConnection(ctx: Context) {
        val address = Prefs.protectAddress(ctx)
        val token = Prefs.protectToken(ctx)
        val base = ProtectClient.baseUrl(address)
        if (base == null) {
            ProtectState.update { it.copy(testing = false, testOk = false, testMessage = noAddressMessage(address)) }
            return
        }
        ProtectState.update { it.copy(testing = true, testOk = null, testMessage = null) }
        Thread {
            val result = runCatching {
                val h = ProtectClient.health(base)
                if (h.tokenRequired && token.isBlank()) throw ProtectClient.ApiException(401, "")
                if (h.tokenRequired) ProtectClient.verifyToken(base, token)
                val where = if (h.device.equals("cpu", ignoreCase = true)) "CPU" else "GPU (${h.device})"
                "Connected. AI Poisoner backend ${h.version}, running on the $where." +
                    if (h.tokenRequired) "" else " No token is needed."
            }
            ProtectState.update {
                it.copy(testing = false, testOk = result.isSuccess,
                    testMessage = result.getOrElse { e -> explain(e, base) })
            }
        }.start()
    }

    /** The old result no longer applies once the address or token is edited. */
    fun clearTest() {
        if (ProtectState.current.let { it.testMessage != null && !it.testing }) {
            ProtectState.update { it.copy(testOk = null, testMessage = null) }
        }
    }

    // ---------------- protecting ----------------

    /** Forgets a finished or failed job so the section is ready for the next photo. */
    fun reset() {
        if (ProtectState.current.busy) return
        ProtectState.update { ProtectState.Snapshot(testOk = it.testOk, testMessage = it.testMessage) }
    }

    fun cancel() {
        synchronized(this) {
            active?.cancelled = true       // the worker also tells the computer to drop the job
            active = null
        }
        ProtectState.update {
            ProtectState.Snapshot(phase = ProtectState.Phase.CANCELLED, name = it.name, sizeBytes = it.sizeBytes,
                original = it.original, testOk = it.testOk, testMessage = it.testMessage)
        }
    }

    /** Starts protecting [uri] (from the picker or a Share). Cancels a job that is still running. */
    fun start(ctx: Context, uri: Uri) {
        val app = ctx.applicationContext     // never the Activity: the thread outlives it
        val job = Job()
        synchronized(this) {
            active?.cancelled = true
            active = job
        }
        // Keep the read grant of a picked file alive after the Activity goes away.
        runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val (name, size) = nameAndSize(app, uri)
        val strength = Prefs.protectStrength(app)
        val mode = Prefs.protectMode(app)
        val address = Prefs.protectAddress(app)
        val token = Prefs.protectToken(app)
        val base = ProtectClient.baseUrl(address)
        val keepTest = ProtectState.current
        ProtectState.update {
            ProtectState.Snapshot(phase = ProtectState.Phase.READING, name = name, sizeBytes = size,
                testOk = keepTest.testOk, testMessage = keepTest.testMessage)
        }
        if (base == null) {
            post(job) { it.copy(phase = ProtectState.Phase.FAILED, message = noAddressMessage(address)) }
            return
        }
        Thread {
            val remote = Remote()
            try {
                run(app, uri, name, base, token, strength, mode, job, remote)
            } catch (e: Throwable) {
                android.util.Log.w("AiPoisoner", "protect failed", e)
                if (!job.cancelled) post(job) { it.copy(phase = ProtectState.Phase.FAILED, message = explain(e, base)) }
            } finally {
                // Cancelled or failed: don't leave work running on the person's computer.
                val id = remote.id
                if (id != null && !remote.saved) {
                    runCatching { ProtectClient.delete(base, token, id) }
                }
                synchronized(this) { if (active === job) active = null }
            }
        }.start()
    }

    private fun run(
        app: Context, uri: Uri, name: String, base: String, token: String, strength: String, mode: String,
        job: Job, remote: Remote,
    ) {
        // 1. Read the photo (and stop early if it is too big for the backend).
        val data = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > ProtectClient.MAX_UPLOAD) {
                    throw TooBig(out.size().toLong())
                }
            }
            out.toByteArray()
        } ?: throw IOException("can't open the photo")
        if (job.cancelled) return
        val original = try { thumbnail(data) } catch (e: Exception) { throw IOException("This file doesn't look like a photo I can read.") }
        post(job) { it.copy(phase = ProtectState.Phase.UPLOADING, original = original, sizeBytes = data.size.toLong()) }

        // 2. Send it.
        val id = ProtectClient.submit(base, token, name, data, strength, mode)
        remote.id = id
        if (job.cancelled) return
        post(job) { it.copy(phase = ProtectState.Phase.QUEUED) }

        // 3. Follow the job. A short network drop is not the end of it: the computer keeps working.
        var lost = 0
        var numbers: ProtectClient.Numbers? = null
        var seconds: Double? = null
        while (true) {
            if (!sleepUnlessCancelled(job)) return
            val st = try {
                ProtectClient.status(base, token, id).also {
                    if (lost > 0) post(job) { s -> s.copy(reconnecting = false) }
                    lost = 0
                }
            } catch (e: ProtectClient.ApiException) {
                throw e            // 401, 404 ... are answers, not a bad connection
            } catch (e: IOException) {
                lost++
                if (lost > MAX_LOST_POLLS) throw e
                if (lost >= 3) post(job) { s -> s.copy(reconnecting = true) }
                continue
            }
            if (job.cancelled) return
            when (st.status) {
                "queued" -> post(job) { it.copy(phase = ProtectState.Phase.QUEUED, queuePosition = st.queuePosition) }
                "running" -> post(job) {
                    it.copy(phase = ProtectState.Phase.RUNNING, step = st.step, steps = st.steps, etaSeconds = st.etaSeconds)
                }
                "done" -> { numbers = st.numbers; seconds = st.seconds; break }
                "error" -> throw IOException(st.error ?: "The backend could not protect this photo.")
                "cancelled" -> {
                    post(job) { it.copy(phase = ProtectState.Phase.CANCELLED) }
                    return
                }
            }
        }

        // 4. Fetch and save the result.
        post(job) { it.copy(phase = ProtectState.Phase.SAVING) }
        val png = downloadWithRetry(base, token, id, job) ?: return
        val thumb = thumbnail(png)
        val (saved, path) = saveToPictures(app, png, name)
        post(job) {
            it.copy(phase = ProtectState.Phase.DONE, protectedThumb = thumb, savedUri = saved, savedPath = path,
                numbers = numbers, seconds = seconds, message = null)
        }
        remote.saved = true
        // Saved safely, so the computer can forget it (it would anyway after an hour).
        runCatching { ProtectClient.delete(base, token, id) }
    }

    /** The job on the computer: the worker's cleanup deletes it unless the result was saved. */
    private class Remote { var id: String? = null; var saved = false }

    private class TooBig(val bytes: Long) : IOException("too big")

    private fun downloadWithRetry(base: String, token: String, id: String, job: Job): ByteArray? {
        var last: IOException? = null
        repeat(4) { attempt ->
            if (job.cancelled) return null
            try {
                return ProtectClient.image(base, token, id)
            } catch (e: ProtectClient.ApiException) {
                throw e
            } catch (e: IOException) {
                last = e
                if (!sleepUnlessCancelled(job, 1500L * (attempt + 1))) return null
            }
        }
        throw last ?: IOException("download failed")
    }

    /** Sleeps in small steps so Cancel is quick. False when cancelled. */
    private fun sleepUnlessCancelled(job: Job, ms: Long = POLL_MS): Boolean {
        var left = ms
        while (left > 0) {
            if (job.cancelled) return false
            val d = minOf(left, 200L)
            Thread.sleep(d)
            left -= d
        }
        return !job.cancelled
    }

    /** Only the current job may publish, so a cancelled one can't overwrite its replacement. */
    private fun post(job: Job, change: (ProtectState.Snapshot) -> ProtectState.Snapshot) {
        synchronized(this) { if (active === job) ProtectState.update(change) }
    }

    // ---------------- files ----------------

    private fun nameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    private fun thumbnail(bytes: ByteArray): Bitmap {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longer = maxOf(info.size.width, info.size.height)
            if (longer > THUMB_SIDE) {
                decoder.setTargetSize(
                    maxOf(1, (info.size.width.toLong() * THUMB_SIDE / longer).toInt()),
                    maxOf(1, (info.size.height.toLong() * THUMB_SIDE / longer).toInt()),
                )
            }
        }
    }

    /** Pictures/AI Poisoner/<name>_protected.png through MediaStore: no storage permission needed (Android 10+). */
    private fun saveToPictures(ctx: Context, png: ByteArray, originalName: String): Pair<Uri, String> {
        val stem = originalName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().ifEmpty { "photo" }.take(80)
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${stem}_protected.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)      // hidden from the gallery until fully written
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create the file in Pictures.")
        try {
            resolver.openOutputStream(uri)?.use { it.write(png) } ?: throw IOException("Couldn't write to Pictures.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        // The system adds " (1)" when the name is taken, so ask what the file ended up being called.
        val finalName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "${stem}_protected.png"
        return uri to "${Environment.DIRECTORY_PICTURES}/$FOLDER/$finalName"
    }

    // ---------------- words ----------------

    private fun noAddressMessage(address: String) =
        if (address.isBlank()) "Enter your computer's address first, for example 192.168.1.20:8765."
        else "“$address” isn't an address I can use. Write it like 192.168.1.20:8765 (no https://)."

    fun explain(e: Throwable, base: String): String {
        val host = base.removePrefix("http://")
        return when (e) {
            is TooBig -> "This photo is larger than 30 MB, which is the most the backend accepts. " +
                "Pick a smaller file; the app doesn't shrink photos for you."
            is ProtectClient.ApiException -> when (e.code) {
                401 -> "The access token is missing or wrong. Copy it again from the window where you started the backend."
                404 -> "Your computer no longer has this job. The backend was probably restarted. Choose the photo again."
                413 -> "The backend says this photo is larger than 30 MB. Pick a smaller file; the app doesn't shrink photos for you."
                429 -> "Your computer already has too many photos waiting. Try again in a minute."
                in 500..599 -> "The backend had an error: ${e.detail.ifEmpty { "HTTP ${e.code}" }}"
                else -> e.detail.ifEmpty { "The backend answered with HTTP ${e.code}." }
            }
            is ProtectClient.NotAipServer -> "$host answered, but it is not an AI Poisoner backend (${e.message}). Check the address and port."
            is UnknownHostException -> "Can't find “$host”. Check the address."
            is SocketTimeoutException, is ConnectException, is NoRouteToHostException ->
                "Can't reach $host. Check that your computer is on, that it is on the same Wi-Fi as this phone, " +
                    "and that the backend is running with --lan. A firewall may be blocking the port."
            is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "The connection to your computer failed."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    /** "45 s", "1 min 20 s". */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return if (s < 60) "$s s" else if (s % 60 == 0) "${s / 60} min" else "${s / 60} min ${s % 60} s"
    }

    /** One line for the progress area; empty when nothing is running. */
    fun statusLine(s: ProtectState.Snapshot): String = when (s.phase) {
        ProtectState.Phase.READING -> "Reading the photo…"
        ProtectState.Phase.UPLOADING -> "Sending it to your computer…"
        ProtectState.Phase.QUEUED ->
            if (s.queuePosition > 0) "Waiting in line on your computer (${s.queuePosition} ahead)…" else "Starting on your computer…"
        ProtectState.Phase.RUNNING -> {
            val step = if (s.steps > 0) "Step ${s.step} of ${s.steps}" else "Working"
            val left = s.etaSeconds?.takeIf { s.step >= 3 }?.let { "  ·  about ${clock(it)} left" } ?: "  ·  working out the time left…"
            step + left
        }
        ProtectState.Phase.SAVING -> "Saving to Pictures…"
        else -> ""
    } + if (s.busy && s.reconnecting) "\nLost contact with your computer, trying again…" else ""

    /** The few plain-language numbers shown under a finished photo. */
    fun numberLines(n: ProtectClient.Numbers?, seconds: Double?): List<String> = buildList {
        n?.psnrDb?.let {
            val how = when {
                it >= 40 -> "the change is practically invisible"
                it >= 35 -> "the change is hard to notice"
                it >= 30 -> "faint grain may show when you zoom in"
                else -> "the grain is visible when you look closely"
            }
            add("Visible change: %.1f dB. Higher is less visible; %s.".format(it, how))
        }
        n?.ssim?.let { add("Similarity to the original: %.2f out of 1.00.".format(it)) }
        n?.maxChange?.let { add("Biggest change to any pixel: %.0f out of 255.".format(it)) }
        n?.timesRandomNoise?.let {
            add("Effect on an AI image encoder: %.1f times what random noise of the same size does. Higher means it is thrown off more.".format(it))
        }
        seconds?.let { add("Took %s on your computer.".format(clock(it.toInt()))) }
    }
}
