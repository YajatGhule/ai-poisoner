package dev.yajatghule.aipoisoner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.IOException

/** What the metadata of an image says about how it was made. */
data class Provenance(val aiHits: List<String>, val c2pa: Boolean)

/**
 * Runs "Check a file" on a background thread and publishes progress and results to [FileCheckState],
 * so rotating or leaving the app doesn't lose them. Only one file at a time: a new one cancels the old.
 */
object FileCheck {
    private const val MAX_METADATA_BYTES = 16 * 1024 * 1024
    private const val MAX_IMAGE_SIDE = 1024
    private const val MAX_TRANSCRIPT_CHARS = 20_000

    private class Job { @Volatile var cancelled = false }

    private var active: Job? = null

    fun cancel() {
        synchronized(this) {
            active?.cancelled = true
            active = null
        }
        FileCheckState.update { it.copy(busy = false, progress = -1f, stage = "", notice = "Cancelled.") }
    }

    /** "12.4 MB". */
    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        else -> "%.2f GB".format(bytes / 1073741824.0)
    }

    /** Starts checking [uri] (from ACTION_OPEN_DOCUMENT). Cancels a check that is still running. */
    fun start(ctx: Context, uri: Uri) {
        val app = ctx.applicationContext     // never the Activity: the thread outlives it
        val job = Job()
        synchronized(this) {
            active?.cancelled = true
            active = job
        }
        // The grant that came with the Activity result can end once the Activity is gone; keep it while we read.
        val persisted = runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        val (name, size) = nameAndSize(app, uri)
        val mime = mimeOf(app, uri, name)
        val image = mime.startsWith("image/")
        FileCheckState.update {
            FileCheckState.Snapshot(busy = true, stage = "Reading file…", name = name, sizeBytes = size, isImage = image)
        }
        Thread {
            try {
                when {
                    image -> checkImage(app, uri, job)
                    mime.startsWith("audio/") || mime.startsWith("video/") -> checkAudio(app, uri, job)
                    else -> post(job) { it.copy(error = "This file type isn't supported. Try an image, audio or video file.") }
                }
            } catch (e: Throwable) {
                android.util.Log.e("AiPoisoner", "file check failed", e)
                if (!job.cancelled) post(job) { it.copy(error = "Couldn't read this file: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                post(job) { it.copy(busy = false, progress = -1f, stage = "") }
                synchronized(this) { if (active === job) active = null }
                if (persisted) runCatching {
                    app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }.start()
    }

    /** Only the current job may publish, so a cancelled one can't overwrite its replacement. */
    private fun post(job: Job, change: (FileCheckState.Snapshot) -> FileCheckState.Snapshot) {
        synchronized(this) { if (active === job) FileCheckState.update(change) }
    }

    private fun nameAndSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
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

    private fun mimeOf(ctx: Context, uri: Uri, name: String): String {
        val reported = ctx.contentResolver.getType(uri)
        if (reported != null && reported != "application/octet-stream") return reported
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: reported ?: ""
    }

    // ---------------- images ----------------

    private fun checkImage(app: Context, uri: Uri, job: Job) {
        // 1. Provenance metadata: if the file says it was made by an AI tool, that beats any classifier.
        post(job) { it.copy(stage = "Checking metadata…") }
        val prov = scanProvenance(readHead(app, uri, MAX_METADATA_BYTES))
        if (job.cancelled) return

        post(job) { it.copy(stage = "Opening image…") }
        val bitmap = decodeImage(app, uri)
        post(job) { it.copy(thumbnail = bitmap) }
        if (job.cancelled) return

        val c2paNote = if (prov.c2pa) "Has C2PA Content Credentials" else null
        if (prov.aiHits.isNotEmpty()) {
            val reason = (prov.aiHits + listOfNotNull(c2paNote)).joinToString(" · ")
            post(job) { it.copy(imageScore = 1f, imageVerdict = Verdict.AI, imageReason = reason) }
            return
        }
        if (!Models.isReady(app, Models.Kind.IMAGE)) {
            post(job) { it.copy(notice = "Download the image detector above first.", imageReason = c2paNote ?: "") }
            return
        }
        post(job) { it.copy(stage = "Analysing…") }
        val p = ImageDetector(Models.file(app, Models.Kind.IMAGE)).use { it.score(bitmap) }
        if (job.cancelled) return
        val reason = listOfNotNull("No AI metadata found; judged by the image classifier", c2paNote).joinToString(" · ")
        post(job) { it.copy(imageScore = p, imageVerdict = Verdict.imageFile(p), imageReason = reason) }
    }

    private fun readHead(ctx: Context, uri: Uri, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } ?: throw IOException("can't open the file")
        return out.toByteArray()
    }

    /** Ported from the Chrome extension: these strings are what generators and editors leave in the file. */
    fun scanProvenance(bytes: ByteArray): Provenance {
        val text = String(bytes, Charsets.ISO_8859_1)    // 1 byte = 1 char, so binary files can be searched as text
        val hits = ArrayList<String>()
        when {
            "compositeWithTrainedAlgorithmicMedia" in text -> hits += "Metadata: edited with generative AI"
            "trainedAlgorithmicMedia" in text -> hits += "Metadata: created by generative AI"
        }
        if ("Made with Google AI" in text) hits += "Metadata: made with Google AI"
        if (STABLE_DIFFUSION.containsMatchIn(text)) hits += "Stable Diffusion settings embedded"
        if ("\"class_type\"" in text && "KSampler" in text) hits += "ComfyUI workflow embedded"
        return Provenance(hits, "c2pa" in text)
    }

    private val STABLE_DIFFUSION = Regex("Steps: \\d+, Sampler: ")

    /** Software ARGB_8888 bitmap whose longer side is at most 1024 px. */
    private fun decodeImage(ctx: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longer = maxOf(w, h)
            if (longer > MAX_IMAGE_SIDE) {
                decoder.setTargetSize(
                    maxOf(1, (w.toLong() * MAX_IMAGE_SIDE / longer).toInt()),
                    maxOf(1, (h.toLong() * MAX_IMAGE_SIDE / longer).toInt()),
                )
            }
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
    }

    // ---------------- audio and video ----------------

    private fun checkAudio(app: Context, uri: Uri, job: Job) {
        if (!Models.isReady(app, Models.Kind.VOICE)) {
            post(job) { it.copy(notice = "Download the AI-voice detector above first.") }
            return
        }
        post(job) { it.copy(stage = "Analysing…", progress = 0f) }
        var transcriber: Transcriber? = if (Models.isReady(app, Models.Kind.TRANSCRIBER)) {
            runCatching { Transcriber(Models.file(app, Models.Kind.TRANSCRIBER)) }.getOrNull()
        } else null
        val text = StringBuilder()
        var shorts = ShortArray(0)
        try {
            VoiceDetector(Models.file(app, Models.Kind.VOICE)).use { detector ->
                val stream = SpeechStream(detector) { s -> post(job) { it.copy(summary = s) } }
                var lastPct = -1
                val seconds = AudioFileDecoder.decode(app, uri, { job.cancelled },
                    onProgress = { f ->
                        val pct = (f * 100).toInt()
                        if (pct != lastPct) { lastPct = pct; post(job) { it.copy(progress = f) } }
                    },
                    onChunk = { chunk ->
                        stream.push(chunk)
                        val t = transcriber
                        if (t != null && text.length < MAX_TRANSCRIPT_CHARS) {
                            if (shorts.size < chunk.size) shorts = ShortArray(chunk.size)
                            for (i in chunk.indices) shorts[i] = (chunk[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            // A Vosk failure shouldn't throw away the voice verdict.
                            val phrase = runCatching { t.accept(shorts, chunk.size) }.getOrElse { transcriber = null; null }
                            if (phrase != null) {
                                text.append(if (text.isEmpty()) "" else " ").append(phrase)
                                val snapshot = text.toString()
                                post(job) { it.copy(transcript = snapshot) }
                            }
                        }
                    })
                if (job.cancelled) return
                val summary = stream.finish()
                transcriber?.partial?.takeIf { it.isNotBlank() && text.length < MAX_TRANSCRIPT_CHARS }
                    ?.let { text.append(if (text.isEmpty()) "" else " ").append(it) }
                val finalText = text.toString().take(MAX_TRANSCRIPT_CHARS)
                post(job) { it.copy(summary = summary, durationSec = seconds, transcript = finalText, progress = 1f) }
            }
        } finally {
            runCatching { transcriber?.close() }
        }
    }
}
