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
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== bitmap) scaled.recycle()
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
