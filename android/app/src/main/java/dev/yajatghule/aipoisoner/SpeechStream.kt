package dev.yajatghule.aipoisoner

/**
 * Speech.analyze for audio that arrives in pieces: same windows (4 s every 2 s from t=0, same skip rules,
 * same short-clip rule), but only the current window is kept in memory.
 */
class SpeechStream(
    private val detector: VoiceDetector,
    private val onWindow: (SpeechSummary) -> Unit = {},
) {
    var summary = SpeechSummary()
        private set
    private val buf = FloatArray(Speech.WINDOW)   // rolling: the window being filled
    private var filled = 0
    private var startSample = 0L                  // where buf[0] sits in the recording

    /** Adds [n] samples of 16 kHz mono audio. */
    fun push(x: FloatArray, n: Int = x.size) {
        var i = 0
        while (i < n) {
            val take = minOf(n - i, Speech.WINDOW - filled)
            System.arraycopy(x, i, buf, filled, take)
            filled += take
            i += take
            if (filled == Speech.WINDOW) {
                summary = if (Speech.usable(buf, 0, Speech.WINDOW))
                    summary.plus(startSample.toFloat() / Speech.RATE, detector.score(buf.copyOf()))
                else summary.plusSkipped()
                onWindow(summary)
                // Slide by one hop: the last HOP samples become the start of the next window.
                System.arraycopy(buf, Speech.HOP, buf, 0, Speech.WINDOW - Speech.HOP)
                filled = Speech.WINDOW - Speech.HOP
                startSample += Speech.HOP
            }
        }
    }

    /** Call once at the end: a 2-4 s recording is judged whole, like Speech.analyze does. */
    fun finish(): SpeechSummary {
        if (startSample == 0L && filled >= Speech.RATE * 2 && filled < Speech.WINDOW && Speech.usable(buf, 0, filled)) {
            summary = summary.plus(0f, detector.score(buf.copyOf(filled)))
            onWindow(summary)
        }
        return summary
    }
}
