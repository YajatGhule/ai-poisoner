package dev.yajatghule.aipoisoner

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** State of "Protect a photo", shared between Protect (worker thread) and MainActivity (reader). Survives rotation. */
object ProtectState {
    enum class Phase { IDLE, READING, UPLOADING, QUEUED, RUNNING, SAVING, DONE, FAILED, CANCELLED }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val name: String = "",
        val sizeBytes: Long = -1,
        val step: Int = 0,
        val steps: Int = 0,
        val etaSeconds: Int? = null,
        val queuePosition: Int = 0,
        /** The link to the computer dropped but the job may still be running there; we keep trying for a while. */
        val reconnecting: Boolean = false,
        /** Plain-language reason for FAILED. */
        val message: String? = null,
        val original: Bitmap? = null,
        val protectedThumb: Bitmap? = null,
        val savedUri: Uri? = null,
        /** "Pictures/AI Poisoner/photo_protected.png" */
        val savedPath: String = "",
        val numbers: ProtectClient.Numbers? = null,
        val seconds: Double? = null,

        // "Test connection", independent of any job
        val testing: Boolean = false,
        val testOk: Boolean? = null,
        val testMessage: String? = null,
    ) {
        val busy
            get() = phase == Phase.READING || phase == Phase.UPLOADING || phase == Phase.QUEUED ||
                phase == Phase.RUNNING || phase == Phase.SAVING
    }

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
