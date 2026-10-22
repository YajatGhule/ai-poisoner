package dev.yajatghule.aipoisoner

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.zip.ZipInputStream

/**
 * The on-device models. They are downloaded once (not bundled) so the APK stays small.
 */
object Models {
    enum class Kind(
        val label: String,
        val url: String,
        /** File (or, for zips, folder) name under filesDir/models. */
        val fileName: String,
        val approxMb: Int,
        val zipped: Boolean = false,
    ) {
        IMAGE(
            "Image detector",
            "https://huggingface.co/onnx-community/ai-image-detection-ONNX/resolve/main/onnx/model_quantized.onnx",
            "image_detector.onnx", 87,
        ),
        VOICE(
            "AI-voice detector",
            "https://github.com/YajatGhule/ai-poisoner/releases/download/voice-model-v2/voice_detector_v2.onnx",
            "voice_detector_v2.onnx", 122,
        ),
        TRANSCRIBER(
            "Speech-to-text (English)",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            "vosk-model-small-en-us-0.15", 40, zipped = true,
        ),
        MUSIC(
            "AI-music detector",
            "https://github.com/YajatGhule/ai-poisoner/releases/download/music-model-v1/ai_music_detector.onnx",
            "ai_music_detector.onnx", 35,
        ),
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }

    fun file(ctx: Context, kind: Kind) = File(dir(ctx), kind.fileName)

    /** Delete models from older app versions (e.g. voice_detector.onnx = v1), so they don't waste space. */
    fun removeOutdated(ctx: Context) {
        val current = Kind.entries.map { it.fileName }.toSet()
        dir(ctx).listFiles()?.forEach { f ->
            val base = f.name.removeSuffix(".zip.part").removeSuffix(".part").removeSuffix(".tmp")
            if (base !in current && f.name !in current) f.deleteRecursively()
        }
    }

    /** Zipped models are extracted into a temp folder and renamed, so a non-empty folder is a complete one. */
    fun isReady(ctx: Context, kind: Kind): Boolean {
        val f = file(ctx, kind)
        return if (kind.zipped) f.isDirectory && !f.list().isNullOrEmpty() else f.exists()
    }

    // Process-wide: the Activity can be recreated while a download thread is still running.
    private val inFlight = Collections.synchronizedSet(mutableSetOf<Kind>())

    private val progress = java.util.concurrent.ConcurrentHashMap<Kind, Float>()

    fun isDownloading(kind: Kind) = kind in inFlight

    /** 0..1 while [kind] is downloading, else null. The UI polls this, so it survives Activity recreation. */
    fun progressOf(kind: Kind): Float? = if (kind in inFlight) progress[kind] ?: 0f else null

    /** Blocking download. Call from a background thread. */
    @Throws(IOException::class)
    fun download(ctx: Context, kind: Kind) {
        if (!inFlight.add(kind)) return     // another thread is already fetching this model
        try {
            downloadLocked(ctx.applicationContext, kind) { progress[kind] = it }
        } finally {
            inFlight.remove(kind)
            progress.remove(kind)
        }
    }

    private fun downloadLocked(ctx: Context, kind: Kind, onProgress: (Float) -> Unit) {
        val target = file(ctx, kind)
        if (isReady(ctx, kind)) return
        val part = File(dir(ctx), kind.fileName + if (kind.zipped) ".zip.part" else ".part")
        // Mobile connections drop; resume from where we stopped instead of starting over.
        var attempt = 0
        while (true) {
            try {
                fetch(kind, part, onProgress)
                break
            } catch (e: IOException) {
                Log.w(TAG, "${kind.label}: download attempt ${attempt + 1} failed", e)
                if (++attempt >= 4) throw e
                Thread.sleep(2000L * attempt)
            }
        }
        if (kind.zipped) {
            // Extract beside the final folder and rename at the end, so a crash mid-unzip leaves nothing "ready".
            val stage = File(dir(ctx), kind.fileName + ".tmp")
            stage.deleteRecursively()
            try {
                stage.mkdirs()
                unzip(part, stage)
                val extracted = File(stage, kind.fileName)
                if (extracted.list().isNullOrEmpty()) throw IOException("${kind.label}: archive did not contain ${kind.fileName}")
                target.deleteRecursively()      // an empty/partial folder from an older version
                if (!extracted.renameTo(target)) throw IOException("Could not save ${kind.label}")
            } catch (e: Exception) {
                part.delete()
                throw if (e is IOException) e else IOException("${kind.label}: ${e.message}", e)
            } finally {
                stage.deleteRecursively()
            }
            part.delete()
        } else if (!part.renameTo(target)) {
            part.delete()
            throw IOException("Could not save ${kind.label}")
        }
        onProgress(1f)
    }

    private fun fetch(kind: Kind, part: File, onProgress: (Float) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        val conn = URL(kind.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code == 416) {
                // The leftover .part is already the full size (or bigger); resuming can never succeed.
                part.delete()
                throw IOException("HTTP 416 for ${kind.label}; restarting")
            }
            if (code !in 200..299) throw IOException("HTTP $code for ${kind.label}")
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            val start = if (resumed) have else 0L
            val length = conn.contentLengthLong
            val total = if (length > 0) start + length else kind.approxMb * 1_000_000L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = start
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress((done.toFloat() / total).coerceAtMost(0.99f))
                        }
                    }
                    if (length > 0 && done != total) throw IOException("${kind.label}: connection closed early")
                    // No Content-Length: a cut-off stream looks like a clean end, so sanity-check the size.
                    if (length <= 0 && done < kind.approxMb * 500_000L) {
                        out.close()
                        part.delete()
                        throw IOException("${kind.label}: download looks truncated")
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private const val TAG = "AiPoisoner"

    private fun unzip(zip: File, into: File) {
        val root = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(into, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad zip entry ${entry.name}")
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }
}
