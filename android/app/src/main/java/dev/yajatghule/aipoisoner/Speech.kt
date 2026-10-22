package dev.yajatghule.aipoisoner

import kotlin.math.sqrt

/**
 * Voice-check logic shared by live listening (VoiceService) and file analysis.
 *
 * A long speech is judged as a whole: every usable 4-second window is scored and kept, and the verdict
 * comes from all of them instead of the last few seconds (which swing with pauses, laughter, music).
 * Stretches that sound AI are reported with timestamps, so an AI segment spliced into a real speech
 * isn't averaged away.
 */
object Speech {
    const val RATE = 16000                 // what the voice and speech-to-text models expect
    const val WINDOW = RATE * 4            // the voice model sees 4 s at a time
    const val HOP = RATE * 2               // a new window every 2 s
    private const val SILENCE = 0.01f

    fun rms(x: FloatArray, from: Int, to: Int): Float {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from)).toFloat()
    }

    /** True if more than 15% of the 100 ms frames are near digital silence (below about -70 dBFS). */
    fun gappy(x: FloatArray, from: Int, to: Int): Boolean {
        val frame = RATE / 10
        var silent = 0
        var frames = 0
        var i = from
        while (i + frame <= to) {
            if (rms(x, i, i + frame) < 3e-4f) silent++
            frames++
            i += frame
        }
        return silent * 100 > frames * 15
    }

    /** Quiet windows and windows with digital silence/dropouts are skipped: they made real voices look synthetic. */
    fun usable(x: FloatArray, from: Int, to: Int) = rms(x, from, to) >= SILENCE && !gappy(x, from, to)

    /**
     * Scores every usable window of a whole recording ([samples]: 16 kHz mono, -1..1).
     * [onProgress] gets the running summary and the fraction done after each window.
     */
    fun analyze(
        samples: FloatArray,
        detector: VoiceDetector,
        cancelled: () -> Boolean = { false },
        onProgress: (SpeechSummary, Float) -> Unit = { _, _ -> },
    ): SpeechSummary {
        var summary = SpeechSummary()
        if (samples.size < WINDOW) {
            // Short clip (2-4 s): judge it whole rather than not at all.
            if (samples.size >= RATE * 2 && usable(samples, 0, samples.size)) summary = summary.plus(0f, detector.score(samples))
            onProgress(summary, 1f)
            return summary
        }
        var start = 0
        while (start + WINDOW <= samples.size && !cancelled()) {
            summary = if (usable(samples, start, start + WINDOW))
                summary.plus(start.toFloat() / RATE, detector.score(samples.copyOfRange(start, start + WINDOW)))
            else summary.plusSkipped()
            onProgress(summary, (start + WINDOW).toFloat() / samples.size)
            start += HOP
        }
        return summary
    }

    /** 75 -> "1:15", 3725 -> "1:02:05". */
    fun clock(seconds: Float): String {
        val s = seconds.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

/** Every scored window of one recording (immutable, so the UI can read it while new windows arrive). */
data class SpeechSummary(
    /** Start time of each scored window, in seconds from the start of the recording. */
    val starts: List<Float> = emptyList(),
    /** AI probability of each scored window. */
    val scores: List<Float> = emptyList(),
    val skipped: Int = 0,
) {
    fun plus(startSec: Float, p: Float) = copy(starts = starts + startSec, scores = scores + p)
    fun plusSkipped() = copy(skipped = skipped + 1)

    val count get() = scores.size

    /** Share of windows that on their own would be called AI. */
    val aiShare: Float get() = if (scores.isEmpty()) 0f else scores.count { it >= Prefs.VOICE_AI_AT }.toFloat() / scores.size

    val median: Float? get() = scores.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** Seconds of audio covered by scored windows (overlap counted once). */
    val coveredSec: Float get() = if (starts.isEmpty()) 0f else stretches { true }.sumOf { (it.endInclusive - it.start).toDouble() }.toFloat()

    /** Time ranges where windows sound AI, merged when they touch or overlap. */
    fun aiStretches() = stretches { it >= Prefs.VOICE_AI_AT }

    private fun stretches(keep: (Float) -> Boolean): List<ClosedFloatingPointRange<Float>> {
        val out = ArrayList<ClosedFloatingPointRange<Float>>()
        val win = Speech.WINDOW.toFloat() / Speech.RATE
        for (i in scores.indices) {
            if (!keep(scores[i])) continue
            val a = starts[i]
            val b = a + win
            val last = out.lastOrNull()
            // Bridge a single borderline window (one hop) so one segment isn't reported as two.
            if (last != null && a <= last.endInclusive + MERGE_GAP_SEC) out[out.size - 1] = last.start..maxOf(last.endInclusive, b)
            else out += a..b
        }
        return out
    }

    /**
     * Verdict for the whole recording, or null until there's enough to judge.
     * Mostly-AI windows (or an AI median) -> AI; almost no AI windows and a low median -> Human; otherwise Unsure.
     */
    val verdict: Verdict?
        get() {
            if (count < MIN_WINDOWS) return null
            val m = median ?: return null
            return when {
                aiShare >= 0.5f || m >= Prefs.VOICE_AI_AT -> Verdict.AI
                aiShare <= 0.1f && m < Prefs.VOICE_HUMAN_BELOW -> Verdict.HUMAN
                else -> Verdict.UNSURE
            }
        }

    /**
     * Mostly real, but with a sustained AI-sounding stretch (e.g. a cloned voice spliced into a real speech).
     * The overall verdict stays Unsure, but the headline should say where to look.
     */
    val partlyAi: Boolean
        get() = verdict == Verdict.UNSURE &&
            aiStretches().sumOf { (it.endInclusive - it.start).toDouble() } >= PARTLY_AI_MIN_SEC

    /** Headline for the whole recording. */
    fun headline(): String? = when (verdict) {
        null -> null
        Verdict.AI -> "Likely an AI voice"
        Verdict.HUMAN -> "Likely a human voice"
        Verdict.UNSURE -> if (partlyAi) "Parts sound AI" else "Unsure"
    }

    /** One line for the UI, e.g. "Likely a human voice · 2% of 85 windows sound AI". */
    fun describe(): String {
        val head = headline() ?: return if (count == 0) "Not enough clear speech yet" else "Need a few more seconds of speech ($count of $MIN_WINDOWS windows)"
        return "$head · ${(aiShare * 100).toInt()}% of $count windows sound AI"
    }

    /** "Sounds AI at 0:42–1:10, 3:05–3:13" (at most [max] ranges), or null if no window sounds AI. */
    fun describeStretches(max: Int = 4): String? {
        val s = aiStretches()
        if (s.isEmpty()) return null
        val shown = s.take(max).joinToString { "${Speech.clock(it.start)}–${Speech.clock(it.endInclusive)}" }
        return "Sounds AI at $shown" + if (s.size > max) " and ${s.size - max} more" else ""
    }

    companion object {
        const val MIN_WINDOWS = 3
        private const val MERGE_GAP_SEC = 2f       // one hop
        private const val PARTLY_AI_MIN_SEC = 8.0  // at least two windows' worth of AI-sounding speech
    }
}
