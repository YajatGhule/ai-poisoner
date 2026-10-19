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
        /** Smoothed over recent windows; this is what the verdict uses. */
        val smoothed: Float? = null,
        val windows: Int = 0,
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
