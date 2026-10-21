package dev.yajatghule.aipoisoner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

private fun softmaxAt(logits: FloatArray, index: Int): Float {
    val max = logits.max()
    val e = logits.map { exp((it - max).toDouble()) }
    return (e[index] / e.sum()).toFloat()
}

/** ViT AI-image classifier (onnx-community/ai-image-detection-ONNX). Input 224x224, labels REAL=0, FAKE=1. */
class ImageDetector(model: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(model.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })

    /** Probability the image is AI-generated. Not thread-safe; call from one thread. */
    fun score(bitmap: Bitmap): Float {
        // Centre square first: squashing a cropped, downscaled on-screen card to 224x224 pushed real photos
        // towards "AI" in testing; a square crop kept the aspect ratio and halved false alarms.
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== square) scaled.recycle()
        if (square !== bitmap) square.recycle()
        val plane = SIZE * SIZE
        val data = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            // ViTFeatureExtractor: rescale 1/255, normalize mean 0.5 std 0.5  ->  x/127.5 - 1
            data.put(i, ((c shr 16) and 0xFF) / 127.5f - 1f)
            data.put(plane + i, ((c shr 8) and 0xFF) / 127.5f - 1f)
            data.put(2 * plane + i, (c and 0xFF) / 127.5f - 1f)
        }
        OnnxTensor.createTensor(env, data, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            session.run(mapOf("pixel_values" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<FloatArray>)[0]
                return softmaxAt(logits, 1)
            }
        }
    }

    override fun close() = session.close()

    companion object { const val SIZE = 224 }
}

/**
 * AI-voice classifier: frozen wav2vec2-base + small trained head (see android/voice-model).
 * Input: 16 kHz mono float samples (any length, ~4 s works best). Output logits [real, ai].
 */
class VoiceDetector(model: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(model.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })

    fun score(samples: FloatArray): Float {
        // Wav2Vec2FeatureExtractor(do_normalize=True): zero mean, unit variance per clip
        val mean = samples.average().toFloat()
        var v = 0.0
        for (s in samples) v += (s - mean) * (s - mean)
        val std = sqrt(v / samples.size + 1e-7).toFloat()
        val data = FloatBuffer.allocate(samples.size)
        for (i in samples.indices) data.put(i, (samples[i] - mean) / std)
        OnnxTensor.createTensor(env, data, longArrayOf(1, samples.size.toLong())).use { input ->
            session.run(mapOf("input_values" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<FloatArray>)[0]
                return softmaxAt(logits, 1)
            }
        }
    }

    override fun close() = session.close()
}

/**
 * AI-music classifier (SONICS SpecTTTra-alpha-5s). Input: 5 s of 16 kHz mono PCM; the spectrogram is computed
 * inside the graph. Output: probability the music is AI-generated. A whole song is the mean over its windows.
 */
class MusicDetector(model: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(model.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })

    /** [window] must be exactly [WINDOW] samples. Not thread-safe; call from one thread. */
    fun score(window: FloatArray): Float {
        require(window.size == WINDOW) { "music window must be $WINDOW samples" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, WINDOW.toLong())).use { input ->
            session.run(mapOf("pcm" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return (out["prob_ai"].get().value as Array<FloatArray>)[0][0]
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val WINDOW = 5 * Speech.RATE
        const val MAX_WINDOWS = 12
        const val SILENCE_RMS = 1e-3f

        /** "Music: Likely AI-generated (87%)". */
        fun describe(p: Float): String = "Music: " + when (Verdict.music(p)) {
            Verdict.AI -> "Likely AI-generated (${(p * 100).toInt()}%)"
            Verdict.UNSURE -> "Unsure (${(p * 100).toInt()}% AI)"
            Verdict.HUMAN -> "Likely human-made (${(p * 100).toInt()}% AI)"
        }
    }
}
