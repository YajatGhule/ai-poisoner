package dev.yajatghule.aipoisoner

import android.content.Context
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
            "https://github.com/YajatGhule/ai-poisoner/releases/download/voice-model-v1/voice_detector_int8.onnx",
            "voice_detector.onnx", 95,
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
        val conn = URL(kind.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} for ${kind.label}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (kind.approxMb * 1_000_000L)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
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
                }
            }
        } finally {
            conn.disconnect()
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
