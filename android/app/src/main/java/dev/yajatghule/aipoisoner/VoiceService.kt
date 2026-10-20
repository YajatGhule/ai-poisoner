package dev.yajatghule.aipoisoner

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Listens to the microphone, or to audio other apps are playing, and continuously:
 *  - transcribes it (Vosk, offline)
 *  - scores 4-second windows with the AI-voice detector
 * Results go to [VoiceState]; the notification shows the current verdict.
 */
class VoiceService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: return START_NOT_STICKY
        stopListening()

        // Must be in the foreground with the matching type *before* touching the mic / projection.
        val type = if (source == SOURCE_PLAYBACK) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(NOTIFICATION_ID, notification("Listening…"), type)

        val record = try {
            if (source == SOURCE_PLAYBACK) playbackRecord(intent) else micRecord()
        } catch (e: Exception) {
            VoiceState.update { it.copy(listening = false, status = "Couldn't start: ${e.message}") }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        worker = Thread({ listen(record, source) }, "voice-listener").apply { start() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun micRecord(): AudioRecord {
        check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "microphone permission not granted" }
        return AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferBytes())
    }

    @SuppressLint("MissingPermission")
    private fun playbackRecord(intent: Intent): AudioRecord {
        check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "microphone permission not granted" }
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, requireNotNull(data) { "no capture permission" })
            ?: error("screen-capture permission was refused")
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { running = false }
        }, Handler(Looper.getMainLooper()))
        projection = mp
        // Apps can opt out of capture; phone and VoIP calls are never capturable on Android.
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        // Capture at the mixer's native 48 kHz and downsample ourselves: asking the
        // playback-capture device for 16 kHz directly returned silence in testing.
        return AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CAPTURE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(bufferBytes(CAPTURE_RATE))
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    private fun bufferBytes(rate: Int = RATE) = maxOf(
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate)

    private fun listen(record: AudioRecord, source: String) {
        val transcriber = load(Models.Kind.TRANSCRIBER) { Transcriber(it) }
        val detector = load(Models.Kind.VOICE) { VoiceDetector(it) }
        val missing = listOfNotNull(
            "AI-voice detector".takeIf { detector == null },
            "speech-to-text".takeIf { transcriber == null },
        )
        VoiceState.update {
            VoiceState.Snapshot(
                listening = true,
                source = source,
                status = if (missing.isEmpty()) "Listening" else "Listening (not downloaded: ${missing.joinToString()})",
            )
        }

        val inRate = record.sampleRate
        val downsampler = if (inRate != RATE) Downsampler(inRate / RATE) else null
        val raw = ShortArray(inRate / 10)               // 100 ms at the capture rate
        val chunk = ShortArray(RATE / 10 + 8)           // 100 ms at 16 kHz
        val window = FloatArray(WINDOW)                  // last 4 s, oldest first
        var filled = 0
        var sinceScore = 0
        val scorer = Executors.newSingleThreadExecutor()
        val busy = AtomicBoolean(false)

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("audio input is busy")
            while (running) {
                val got = record.read(raw, 0, raw.size)
                if (got <= 0) continue
                val n = downsampler?.process(raw, got, chunk) ?: got.also { System.arraycopy(raw, 0, chunk, 0, got) }
                if (n <= 0) continue

                transcriber?.let { t ->
                    val done = t.accept(chunk, n)
                    VoiceState.update { s ->
                        if (done != null) s.copy(transcript = (s.transcript + " " + done).trim().takeLast(4000), partial = "")
                        else s.copy(partial = t.partial)
                    }
                }

                System.arraycopy(window, n, window, 0, WINDOW - n)
                for (i in 0 until n) window[WINDOW - n + i] = chunk[i] / 32768f
                filled = minOf(WINDOW, filled + n)
                sinceScore += n

                if (detector != null && filled == WINDOW && sinceScore >= HOP && !busy.get()) {
                    sinceScore = 0
                    // Skip quiet windows, and windows with stretches of digital silence (a video ending,
                    // dropouts): in testing those made real voices look synthetic.
                    if (rms(window, 0, WINDOW) < SILENCE || gappy(window)) continue
                    val clip = window.copyOf()
                    busy.set(true)
                    scorer.execute {
                        try {
                            val p = detector.score(clip)
                            Log.d(TAG, "voice window AI %.2f".format(p))
                            VoiceState.update { s ->
                                val smooth = s.smoothed?.let { it * 0.6f + p * 0.4f } ?: p
                                s.copy(latest = p, smoothed = smooth, windows = s.windows + 1)
                            }
                            notify(VoiceState.current)
                        } catch (e: Exception) {
                            Log.e(TAG, "voice scoring failed", e)
                        } finally {
                            busy.set(false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            VoiceState.update { it.copy(status = "Stopped: ${e.message}") }
        } finally {
            runCatching { record.stop() }
            record.release()
            scorer.shutdown()
            scorer.awaitTermination(5, TimeUnit.SECONDS)
            detector?.close()
            transcriber?.close()
            VoiceState.update { it.copy(listening = false, partial = "") }
            // Switching mic <-> phone audio starts a new session before this one finishes; don't kill it.
            Handler(Looper.getMainLooper()).post {
                if (!running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
    }

    private fun <T> load(kind: Models.Kind, make: (java.io.File) -> T): T? =
        if (Models.isReady(this, kind)) runCatching { make(Models.file(this, kind)) }.getOrNull() else null

    private fun rms(x: FloatArray, from: Int, to: Int): Float {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from)).toFloat()
    }

    /** True if more than 15% of the 100 ms frames are near digital silence (below about -70 dBFS). */
    private fun gappy(x: FloatArray): Boolean {
        val frame = RATE / 10
        var silent = 0
        var frames = 0
        var i = 0
        while (i + frame <= x.size) {
            if (rms(x, i, i + frame) < 3e-4f) silent++
            frames++
            i += frame
        }
        return silent * 100 > frames * 15
    }

    private fun stopListening() {
        running = false
        worker?.join(2000)
        worker = null
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice check", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AI Poisoner is listening")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun notify(s: VoiceState.Snapshot) {
        val p = s.smoothed ?: return
        val v = Verdict.voice(p)
        val text = when (v) {
            Verdict.AI -> "Likely AI voice (${(p * 100).toInt()}%)"
            Verdict.UNSURE -> "Unsure (${(p * 100).toInt()}% AI)"
            Verdict.HUMAN -> "Likely a human voice"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val RATE = 16000                          // what both models expect
        private const val CAPTURE_RATE = 48000
        private const val WINDOW = RATE * 4             // model sees 4 s
        private const val HOP = RATE * 2                // new verdict every 2 s
        private const val SILENCE = 0.01f
        private const val TAG = "AiPoisoner"
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL = "voice"

        const val EXTRA_SOURCE = "source"
        const val SOURCE_MIC = "mic"
        const val SOURCE_PLAYBACK = "playback"
        private const val EXTRA_RESULT_CODE = "code"
        private const val EXTRA_RESULT_DATA = "data"
        private const val ACTION_STOP = "stop"

        fun startMic(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, VoiceService::class.java).putExtra(EXTRA_SOURCE, SOURCE_MIC))

        fun startPlayback(ctx: Context, resultCode: Int, data: Intent) =
            ctx.startForegroundService(Intent(ctx, VoiceService::class.java)
                .putExtra(EXTRA_SOURCE, SOURCE_PLAYBACK)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data))

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_STOP))
    }
}
