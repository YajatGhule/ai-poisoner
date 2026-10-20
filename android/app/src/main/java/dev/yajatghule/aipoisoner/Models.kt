package dev.yajatghule.aipoisoner

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The three on-device models. They are downloaded once (not bundled) so the APK stays small.
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
            "https://github.com/YajatGhule/ai-poisoner/releases/download/voice-model-v1/voice_detector.onnx",
            "voice_detector.onnx", 66,
        ),
        TRANSCRIBER(
            "Speech-to-text (English)",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            "vosk-model-small-en-us-0.15", 40, zipped = true,
        ),
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }

    fun file(ctx: Context, kind: Kind) = File(dir(ctx), kind.fileName)

    fun isReady(ctx: Context, kind: Kind) = file(ctx, kind).exists()

    /** Blocking download. Call from a background thread. [onProgress] gets 0..1. */
    @Throws(IOException::class)
    fun download(ctx: Context, kind: Kind, onProgress: (Float) -> Unit) {
        val target = file(ctx, kind)
        if (target.exists()) return
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
            unzip(part, dir(ctx))
            part.delete()
            if (!target.exists()) throw IOException("${kind.label}: archive did not contain ${kind.fileName}")
        } else if (!part.renameTo(target)) {
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
