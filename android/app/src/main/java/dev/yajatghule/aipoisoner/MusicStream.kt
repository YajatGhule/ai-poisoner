package dev.yajatghule.aipoisoner

import java.util.Random

/**
 * Picks the 5 s windows MusicDetector will score from audio that arrives in pieces. Non-overlapping windows from
 * t=0; near-silent ones are dropped; a remainder of 2.5 s or more is zero-padded. At most
 * [MusicDetector.MAX_WINDOWS] are kept (reservoir sampling, so they are spread over a file of unknown
 * length), which bounds memory to about 4 MB however long the file is.
 */
class MusicStream {
    private val buf = FloatArray(MusicDetector.WINDOW)
    private var filled = 0
    private var seen = 0            // loud windows offered so far
    private var windows = 0         // all windows, loud or not
    private val kept = ArrayList<FloatArray>()
    private val random = Random(1)

    /** Adds [n] samples of 16 kHz mono audio. */
    fun push(x: FloatArray, n: Int = x.size) {
        var i = 0
        while (i < n) {
            val take = minOf(n - i, buf.size - filled)
            System.arraycopy(x, i, buf, filled, take)
            filled += take
            i += take
            if (filled == buf.size) { offer(); filled = 0 }
        }
    }

    private fun offer() {
        windows++
        if (Speech.rms(buf, 0, buf.size) < MusicDetector.SILENCE_RMS) return
        seen++
        if (kept.size < MusicDetector.MAX_WINDOWS) kept += buf.copyOf()
        else {
            val slot = random.nextInt(seen)
            if (slot < kept.size) System.arraycopy(buf, 0, kept[slot], 0, buf.size)
        }
    }

    /** Call once at the end. Returns the windows to score (empty if the audio was silent). */
    fun finish(): List<FloatArray> {
        if (filled >= buf.size / 2 || (windows == 0 && filled > 0)) {
            java.util.Arrays.fill(buf, filled, buf.size, 0f)
            offer()
        }
        filled = 0
        return kept
    }
}
