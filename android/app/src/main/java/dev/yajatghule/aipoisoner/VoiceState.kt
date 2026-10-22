package dev.yajatghule.aipoisoner

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Live state of the voice check, shared between VoiceService (writer) and MainActivity (reader). */
object VoiceState {
    data class Snapshot(
        val listening: Boolean = false,
        val source: String = "",
        val status: String = "",
        val transcript: String = "",
        val partial: String = "",
        /** AI probability of the latest 4-second window. */
        val latest: Float? = null,
        /** Smoothed over the last few windows: the "right now" verdict. */
        val smoothed: Float? = null,
        val windows: Int = 0,
        /** Every window of this session: the whole-speech verdict, AI stretches and timeline. */
        val summary: SpeechSummary = SpeechSummary(),
        /** Seconds of audio heard this session. */
        val heardSec: Float = 0f,
        /** Running mean AI probability over the 5 s music windows scored this session (null until the first). */
        val musicMean: Float? = null,
        val musicWindows: Int = 0,
    )

    @Volatile
    var current = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun update(change: (Snapshot) -> Snapshot) {
        val next = synchronized(this) { change(current).also { current = it } }
        main.post { listeners.forEach { it(next) } }
    }

    fun observe(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun remove(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }
}
