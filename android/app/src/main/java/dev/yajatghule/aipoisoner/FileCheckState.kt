package dev.yajatghule.aipoisoner

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** State of the "Check a file" feature, shared between FileCheck (writer thread) and MainActivity (reader). */
object FileCheckState {
    data class Snapshot(
        val busy: Boolean = false,
        /** 0..1, or -1 when the amount of work isn't known. */
        val progress: Float = -1f,
        /** What is happening while [busy], e.g. "Reading file…". */
        val stage: String = "",
        val name: String = "",
        val sizeBytes: Long = -1,
        val isImage: Boolean = false,
        /** "Couldn't read this file: …" or "This file type isn't supported…". */
        val error: String? = null,
        /** Not a failure, just something the person has to do first (e.g. download a model). */
        val notice: String? = null,

        // Image result
        val thumbnail: Bitmap? = null,
        val imageScore: Float? = null,
        val imageVerdict: Verdict? = null,
        val imageReason: String = "",

        // Audio / video result
        val summary: SpeechSummary = SpeechSummary(),
        val durationSec: Float = 0f,
        val transcript: String = "",
        /** Reserved for a future AI-music detector; the UI keeps its line hidden while this is null. */
        val music: String? = null,
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
