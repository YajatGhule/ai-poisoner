package dev.yajatghule.aipoisoner

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming integer-factor decimator (e.g. 48 kHz -> 16 kHz) with a windowed-sinc low-pass,
 * so content above the new Nyquist doesn't alias into the speech band. Keeps state between chunks.
 */
class Downsampler(private val factor: Int, taps: Int = 31) {
    private val h: FloatArray = run {
        val fc = 0.45 / factor                       // cutoff just under the new Nyquist (cycles/sample)
        val k = DoubleArray(taps) { i ->
            val m = i - (taps - 1) / 2.0
            val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            sinc * (0.54 - 0.46 * cos(2 * PI * i / (taps - 1)))   // Hamming window
        }
        val sum = k.sum()
        FloatArray(taps) { (k[it] / sum).toFloat() }
    }
    private val history = FloatArray(taps - 1)
    private var phase = 0

    /** Filters [n] samples of [input] and writes every [factor]-th output into [out]. Returns how many were written. */
    fun process(input: ShortArray, n: Int, out: ShortArray): Int {
        val buf = FloatArray(history.size + n)
        System.arraycopy(history, 0, buf, 0, history.size)
        for (i in 0 until n) buf[history.size + i] = input[i].toFloat()
        var written = 0
        var j = history.size + phase
        while (j < buf.size && written < out.size) {
            var acc = 0f
            for (k in h.indices) acc += h[k] * buf[j - k]
            out[written++] = acc.roundToInt().coerceIn(-32768, 32767).toShort()
            j += factor
        }
        phase = j - buf.size
        System.arraycopy(buf, buf.size - history.size, history, 0, history.size)
        return written
    }
}
