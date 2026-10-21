package dev.yajatghule.aipoisoner

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Decodes the first audio track of any audio/video file to 16 kHz mono floats, streamed in chunks,
 * so an hour-long lecture never needs more than a second or two of audio in memory.
 */
object AudioFileDecoder {
    private const val CHUNK = Speech.RATE            // 1 s per callback
    private const val TIMEOUT_US = 10_000L
    private const val STALL_MS = 15_000L             // give up if the codec makes no progress this long

    /**
     * Blocking; call from a background thread. [onChunk] gets 16 kHz mono samples in -1..1 (the last chunk may be short).
     * [onProgress] gets 0..1 when the file reports its duration. Returns seconds of audio delivered.
     * Stops early (without error) when [cancelled] turns true. Throws IOException if there's no audio track or decoding fails.
     */
    fun decode(
        ctx: Context,
        uri: Uri,
        cancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        onChunk: (FloatArray) -> Unit,
    ): Float {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ctx, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("this file has no audio track")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            // Output format can differ from the track's (SBR AAC, mid-stream changes), so it is re-read on INFO_OUTPUT_FORMAT_CHANGED.
            var rate = inFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, Speech.RATE)
            var channels = inFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = if (rate == Speech.RATE) null else StreamResampler(rate)

            val chunk = FloatArray(CHUNK)
            var filled = 0
            var delivered = 0L
            fun emit(x: FloatArray) {
                var i = 0
                while (i < x.size) {
                    val take = minOf(x.size - i, CHUNK - filled)
                    for (k in 0 until take) chunk[filled + k] = x[i + k].coerceIn(-1f, 1f)
                    filled += take
                    i += take
                    if (filled == CHUNK) { onChunk(chunk.copyOf()); delivered += CHUNK; filled = 0 }
                }
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastProgressAt = SystemClock.uptimeMillis()
            while (!outputDone && !cancelled()) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        lastProgressAt = SystemClock.uptimeMillis()
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o >= 0 -> {
                        lastProgressAt = SystemClock.uptimeMillis()
                        if (info.size > 0) {
                            val bb = dec.getOutputBuffer(o)!!
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            bb.order(ByteOrder.nativeOrder())
                            val mono = toMono(bb, info.size, channels, encoding)
                            emit(resampler?.process(mono, mono.size) ?: mono)
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        val newRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
                        if (newRate != rate) {
                            // Finish the old stream's tail before switching, or its last few ms would be lost.
                            resampler?.let { emit(it.finish()) }
                            rate = newRate
                            resampler = if (rate == Speech.RATE) null else StreamResampler(rate)
                        }
                        channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        encoding = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    else -> {}   // INFO_TRY_AGAIN_LATER: loop again, but the stall check below stops it being forever
                }
                if (SystemClock.uptimeMillis() - lastProgressAt > STALL_MS) throw IOException("the audio decoder stopped responding")
            }
            if (cancelled()) return delivered.toFloat() / Speech.RATE
            resampler?.let { emit(it.finish()) }
            if (filled > 0) { onChunk(chunk.copyOf(filled)); delivered += filled }
            onProgress(1f)
            return delivered.toFloat() / Speech.RATE
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.intOr(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default

    /** Interleaved PCM in [bb] (position..limit = [size] bytes) -> mono floats, averaging all channels. */
    private fun toMono(bb: ByteBuffer, size: Int, channels: Int, encoding: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val s = bb.asFloatBuffer()
                FloatArray(size / 4 / ch) { f ->
                    var sum = 0f
                    for (c in 0 until ch) sum += s.get(f * ch + c)
                    sum / ch
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val start = bb.position()
                FloatArray(size / ch) { f ->
                    var sum = 0f
                    for (c in 0 until ch) sum += ((bb.get(start + f * ch + c).toInt() and 0xFF) - 128) / 128f
                    sum / ch
                }
            }
            else -> {
                val s = bb.asShortBuffer()
                FloatArray(size / 2 / ch) { f ->
                    var sum = 0f
                    for (c in 0 until ch) sum += s.get(f * ch + c)
                    sum / ch / 32768f
                }
            }
        }
    }
}

/**
 * Streaming sample-rate converter to 16 kHz for any source rate (44.1k, 22.05k, 8k, 48k...), keeping state between calls.
 *
 * Windowed-sinc interpolation: each output sample is a Hann-windowed sinc sum over the input around its exact
 * position (m * srcRate/16000). The sinc's cutoff is 95% of the lower of the two Nyquist rates, so downsampling
 * is low-passed (no aliasing) and upsampling doesn't ring. One filter handles integer and fractional ratios alike,
 * which keeps the phase exact (no drift: positions are computed from the output index, not accumulated).
 * Kernel values come from a 256x oversampled table with linear interpolation.
 */
internal class StreamResampler(srcRate: Int, dstRate: Int = Speech.RATE) {
    private val ratio = srcRate.toDouble() / dstRate        // input samples per output sample
    private val cut = minOf(1.0, 1.0 / ratio) * 0.95        // cycles per input sample
    private val half = ceil(ZEROS / cut).toInt()            // kernel reaches this many input samples either side
    private val table = FloatArray(half * RES + 2) { i ->
        val x = i.toDouble() / RES
        val sinc = if (x == 0.0) cut else sin(PI * cut * x) / (PI * x)
        (sinc * (0.5 + 0.5 * cos(PI * x / half))).toFloat()
    }
    private var pending = FloatArray(0)    // input not yet fully consumed
    private var base = 0L                  // absolute input index of pending[0]
    private var totalIn = 0L
    private var outIndex = 0L

    fun process(input: FloatArray, n: Int): FloatArray {
        val merged = FloatArray(pending.size + n)
        System.arraycopy(pending, 0, merged, 0, pending.size)
        System.arraycopy(input, 0, merged, pending.size, n)
        pending = merged
        totalIn += n
        return drain(false)
    }

    /** Emits the outputs that still lie inside the input (beyond its end counts as silence). */
    fun finish(): FloatArray = drain(true)

    private fun drain(flush: Boolean): FloatArray {
        var out = FloatArray((pending.size / ratio).toInt() + 16)
        var count = 0
        val end = base + pending.size
        while (true) {
            val t = outIndex * ratio
            val c = floor(t).toLong()
            if (flush) { if (t >= totalIn) break } else if (c + half >= end) break   // not enough lookahead yet
            var acc = 0f
            val lo = maxOf(c - half + 1, base)
            val hi = minOf(c + half, end - 1)
            for (idx in lo..hi) {
                val p = abs(idx - t) * RES
                val i = p.toInt()
                val f = (p - i).toFloat()
                acc += pending[(idx - base).toInt()] * (table[i] * (1 - f) + table[i + 1] * f)
            }
            if (count == out.size) out = out.copyOf(out.size * 2)
            out[count++] = acc
            outIndex++
        }
        // Drop input the next output can no longer reach.
        val keepFrom = maxOf(base, floor(outIndex * ratio).toLong() - half + 1)
        val drop = (keepFrom - base).toInt().coerceIn(0, pending.size)
        if (drop > 0) { pending = pending.copyOfRange(drop, pending.size); base += drop }
        return out.copyOf(count)
    }

    private companion object {
        const val ZEROS = 8.0     // sinc zero-crossings kept on each side
        const val RES = 256
    }
}
