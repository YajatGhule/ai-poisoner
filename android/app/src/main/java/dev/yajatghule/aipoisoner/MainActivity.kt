package dev.yajatghule.aipoisoner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** One screen: model downloads, the image-overlay switch, and the live voice check. */
class MainActivity : Activity() {
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var a11yStatus: TextView
    private lateinit var verdict: TextView
    private lateinit var meter: ProgressBar
    private lateinit var detail: TextView
    private lateinit var sessionLine: TextView
    private lateinit var timeline: TimelineView
    private lateinit var transcript: TextView
    private lateinit var liveMusic: TextView
    private lateinit var listenMic: Button
    private lateinit var listenPhone: Button
    private lateinit var stop: Button
    private val modelRows = mutableMapOf<Models.Kind, Pair<TextView, Button>>()
    private var pending: (() -> Unit)? = null
    private val onVoice: (VoiceState.Snapshot) -> Unit = { render(it) }

    // "Check a file" views; results come from FileCheckState so they survive rotation.
    private lateinit var fileName: TextView
    private lateinit var fileStatus: TextView
    private lateinit var fileProgress: ProgressBar
    private lateinit var fileCancel: Button
    private lateinit var fileNotice: TextView
    private lateinit var fileThumb: ImageView
    private lateinit var fileVerdict: TextView
    private lateinit var fileLine: TextView
    private lateinit var fileDetail: TextView
    private lateinit var fileTimeline: TimelineView
    private lateinit var fileDuration: TextView
    /** File-check music verdict; hidden unless the music model scored the file. */
    private lateinit var musicLine: TextView
    private lateinit var fileTranscriptHead: TextView
    private lateinit var fileTranscript: TextView
    private lateinit var fileTranscriptToggle: Button
    private var transcriptExpanded = false
    private val onFile: (FileCheckState.Snapshot) -> Unit = { renderFile(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { Models.removeOutdated(this) }.start()
        setContentView(buildUi())
        if (savedInstanceState == null) checkShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkShared(intent)
    }

    /** A file shared to the app ("Share → AI Poisoner") or opened with it goes straight to the file check. */
    private fun checkShared(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_VIEW -> intent.data
            // Debug builds only: `adb shell am start -n …/.MainActivity --es path /sdcard/Android/data/<pkg>/files/x.m4a`
            // (the app's own folder needs no permission), so demos and tests can skip the system picker.
            else -> intent?.getStringExtra("path")?.takeIf { applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }
                ?.let { android.net.Uri.fromFile(java.io.File(it)) }
        } ?: return
        transcriptExpanded = false
        FileCheck.start(applicationContext, uri)
    }

    override fun onResume() {
        super.onResume()
        a11yStatus.text = if (imageServiceOn()) "● On — labels appear on images in other apps"
        else "○ Off — turn on “AI Poisoner image labels” in Accessibility settings"
        a11yStatus.setTextColor(if (imageServiceOn()) GREEN else MUTE)
        ticker.run()
        VoiceState.observe(onVoice)
        FileCheckState.observe(onFile)
    }

    override fun onPause() {
        a11yStatus.removeCallbacks(ticker)
        VoiceState.remove(onVoice)
        FileCheckState.remove(onFile)
        super.onPause()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(32))
        }
        col += label("HIGH ART · DETECTOR", 11f, MUTE, mono = true)
        col += TextView(this).apply {
            text = "AI Poisoner"
            textSize = 34f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(INK)
            setPadding(0, px(4), 0, px(18))
        }

        // Models
        col += section("On-device models")
        col += label("Downloaded once. Nothing you see or hear is uploaded.", 13f, MUTE)
        for (kind in Models.Kind.entries) col += modelRow(kind)

        // Images
        col += section("Images on screen")
        a11yStatus = label("", 14f, MUTE)
        col += a11yStatus
        col += button("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        col += label(
            "Greyed-out switch? Android blocks this for apps installed from an APK. Open Settings → Apps → AI Poisoner → ⋮ → " +
                "“Allow restricted settings”, then try again.", 12f, MUTE)
        col += toggle("Label images", Prefs.imagesEnabled(this)) { on ->
            Prefs.setImagesEnabled(this, on); ImageScanService.instance?.refresh()
        }
        col += toggle("Hide images rated AI", Prefs.coverAi(this)) { on ->
            Prefs.setCoverAi(this, on); ImageScanService.instance?.refresh()
        }

        // Voice
        col += section("Voice check")
        col += label("Is this voice real? Works for long speeches: every 4-second stretch is checked, the verdict covers " +
            "the whole recording, and any parts that sound AI are listed with times.", 13f, MUTE)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listenMic = button("Microphone") { withAudioPermission { VoiceService.startMic(this) } }
        listenPhone = button("Phone audio") { withAudioPermission { askForPlaybackCapture() } }
        row.addView(listenMic, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(8) })
        row.addView(listenPhone, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col += row
        stop = button("Stop listening") { VoiceService.stop(this) }
        col += stop

        verdict = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(INK)
            setPadding(0, px(18), 0, px(6))
        }
        col += verdict
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col += meter
        sessionLine = label("", 13f, INK)
        col += sessionLine
        liveMusic = label("", 13f, INK).apply { visibility = View.GONE }
        col += liveMusic
        timeline = TimelineView(this).apply { visibility = View.GONE }
        col += timeline
        detail = label("", 12f, MUTE)
        col += detail
        col += label("TRANSCRIPT", 11f, MUTE, mono = true).apply { setPadding(0, px(18), 0, px(6)) }
        transcript = TextView(this).apply {
            textSize = 15f
            setTextColor(INK)
            setLineSpacing(0f, 1.25f)
            background = card()
            setPadding(px(14), px(12), px(14), px(12))
            minHeight = px(120)
        }
        col += transcript
        col += label(
            "Phone audio works for most media apps (YouTube, Instagram, voice notes played aloud). " +
                "Android never lets apps record phone or WhatsApp calls; put the call on speaker and use Microphone instead.\n\n" +
                "Verdicts are estimates from a small model, not proof. Don't accuse anyone based on this alone.",
            12f, MUTE).apply { setPadding(0, px(16), 0, 0) }

        buildFileSection(col)

        return ScrollView(this).apply {
            addView(col)
            // Android 15 draws apps edge-to-edge; keep content clear of the status and navigation bars.
            // The bar insets go on the scroll view (clipped), so scrolled text never slides under the clock.
            clipToPadding = true
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                col.setPadding(px(20), px(28), px(20), px(32))
                insets
            }
        }
    }

    private fun buildFileSection(col: LinearLayout) {
        col += section("Check a file")
        col += label("Pick an image, audio or video file. Images are checked for AI metadata and by the image detector; " +
            "audio and video get the same whole-recording voice check and a transcript. The file stays on your phone.", 13f, MUTE)
        col += button("Choose a file") { chooseFile() }
        fileName = label("", 14f, INK).apply { setPadding(0, px(10), 0, px(2)) }
        col += fileName
        fileStatus = label("", 13f, MUTE)
        col += fileStatus
        fileProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col += fileProgress
        fileCancel = button("Cancel") { FileCheck.cancel() }
        col += fileCancel
        fileNotice = label("", 14f, GOLD)
        col += fileNotice

        fileThumb = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = px(200)
            scaleType = ImageView.ScaleType.FIT_START
            visibility = View.GONE
        }
        col += fileThumb
        fileVerdict = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(INK)
            setPadding(0, px(14), 0, px(6))
        }
        col += fileVerdict
        fileLine = label("", 13f, INK)
        col += fileLine
        fileTimeline = TimelineView(this).apply { visibility = View.GONE }
        col += fileTimeline
        fileDetail = label("", 12f, MUTE)
        col += fileDetail
        fileDuration = label("", 12f, MUTE)
        col += fileDuration
        musicLine = label("", 13f, INK).apply { visibility = View.GONE }
        col += musicLine
        fileTranscriptHead = label("TRANSCRIPT", 11f, MUTE, mono = true).apply { setPadding(0, px(18), 0, px(6)) }
        col += fileTranscriptHead
        fileTranscript = TextView(this).apply {
            textSize = 15f
            setTextColor(INK)
            setLineSpacing(0f, 1.25f)
            background = card()
            setPadding(px(14), px(12), px(14), px(12))
        }
        col += fileTranscript
        fileTranscriptToggle = button("Show full transcript") {
            transcriptExpanded = !transcriptExpanded
            renderFile(FileCheckState.current)
        }
        col += fileTranscriptToggle
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "audio/*", "video/*"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_FILE)
    }

    private fun renderFile(s: FileCheckState.Snapshot) {
        fun show(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
        fun text(v: TextView, t: String?) { v.text = t ?: ""; show(v, !t.isNullOrEmpty()) }
        text(fileName, if (s.name.isEmpty()) null else listOf(s.name, FileCheck.formatSize(s.sizeBytes)).filter { it.isNotEmpty() }.joinToString("  ·  "))
        show(fileProgress, s.busy)
        show(fileCancel, s.busy)
        fileProgress.isIndeterminate = s.progress < 0f
        if (s.progress >= 0f) fileProgress.progress = (s.progress * 100).toInt()
        text(fileStatus, if (!s.busy) null else if (s.progress >= 0f) "Analysing… ${(s.progress * 100).toInt()}%" else s.stage)
        text(fileNotice, s.error ?: s.notice)
        fileNotice.setTextColor(if (s.error != null) Verdict.AI.color else GOLD)

        // Image result
        val bmp = s.thumbnail
        if (s.isImage && bmp != null) fileThumb.setImageBitmap(bmp) else fileThumb.setImageDrawable(null)
        show(fileThumb, s.isImage && bmp != null)

        // Audio / video result
        val sum = s.summary
        val audio = !s.isImage && s.name.isNotEmpty() && s.error == null && (sum.count + sum.skipped > 0 || s.durationSec > 0f)
        val iv = s.imageVerdict
        when {
            s.isImage && iv != null -> {
                fileVerdict.text = when (iv) {
                    Verdict.AI -> "Likely AI-generated"
                    Verdict.UNSURE -> "Unsure"
                    Verdict.HUMAN -> "Likely not AI"
                }
                fileVerdict.setTextColor(iv.color)
                text(fileLine, "${((s.imageScore ?: 0f) * 100).toInt()}% AI  ·  ${s.imageReason}")
            }
            audio -> {
                val whole = sum.verdict
                fileVerdict.text = sum.headline() ?: sum.describe()
                fileVerdict.setTextColor(if (sum.partlyAi) Verdict.AI.color else whole?.color ?: INK)
                text(fileLine, if (sum.headline() == null) null else listOfNotNull(sum.describe(), sum.describeStretches()).joinToString("\n"))
            }
            else -> { fileVerdict.text = ""; text(fileLine, if (s.isImage) s.imageReason else null) }
        }
        show(fileVerdict, fileVerdict.text.isNotEmpty())
        fileTimeline.show(if (audio) sum.scores else emptyList())
        text(fileDetail, if (audio && sum.skipped > 0) "${sum.skipped} quiet or broken-up windows were skipped" else null)
        text(fileDuration, if (audio && s.durationSec > 0f) "Duration ${Speech.clock(s.durationSec)}" else null)
        text(musicLine, if (audio) s.music else null)

        // Transcript, shortened until asked for
        val full = s.transcript
        val showTranscript = audio && full.isNotEmpty()
        show(fileTranscriptHead, showTranscript)
        show(fileTranscript, showTranscript)
        val long = full.length > TRANSCRIPT_PREVIEW
        fileTranscript.text = if (long && !transcriptExpanded) full.take(TRANSCRIPT_PREVIEW).trimEnd() + "…" else full
        show(fileTranscriptToggle, showTranscript && long)
        fileTranscriptToggle.text = if (transcriptExpanded) "Show less" else "Show full transcript"
    }

    private fun modelRow(kind: Models.Kind): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6), 0, px(6))
        }
        val text = label("", 14f, INK)
        val get = button("Download") { download(kind) }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(get)
        modelRows[kind] = text to get
        return row
    }

    private fun refreshModelRow(kind: Models.Kind) {
        val (text, get) = modelRows[kind] ?: return
        val ready = Models.isReady(this, kind)
        val progress = if (ready) null else Models.progressOf(kind)
        text.text = when {
            ready -> "${kind.label}  ✓"
            progress != null -> "${kind.label} · ${(progress * 100).toInt()}%"
            else -> "${kind.label} · ${kind.approxMb} MB"
        }
        get.visibility = if (ready) View.GONE else View.VISIBLE
        get.isEnabled = progress == null
        get.text = if (progress != null) "Downloading…" else "Download"
    }

    // Rows poll the shared download state, so progress keeps showing after rotation / leaving and coming back.
    private val ticker = object : Runnable {
        override fun run() {
            Models.Kind.entries.forEach { refreshModelRow(it) }
            a11yStatus.removeCallbacks(this)
            val busy = Models.Kind.entries.any { Models.isDownloading(it) }
            if (busy || android.os.SystemClock.uptimeMillis() < pollUntil) a11yStatus.postDelayed(this, 500)
        }
    }
    private var pollUntil = 0L   // keep polling briefly after a tap, before the download thread registers

    private fun download(kind: Models.Kind) {
        if (Models.isDownloading(kind)) return
        val app = applicationContext
        Thread {
            val error = runCatching { Models.download(app, kind) }.exceptionOrNull()
            // The model may be ready even if this Activity is gone; the service should still pick it up.
            if (error == null && kind == Models.Kind.IMAGE) runOnUiThread { ImageScanService.instance?.refresh() }
            if (error != null) {
                android.util.Log.e("AiPoisoner", "${kind.label} download failed", error)
                runOnUiThread { Toast.makeText(app, "${kind.label}: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
        pollUntil = android.os.SystemClock.uptimeMillis() + 3000
        ticker.run()
    }

    private fun verdictText(v: Verdict) = when (v) {
        Verdict.AI -> "Likely an AI voice"
        Verdict.UNSURE -> "Unsure"
        Verdict.HUMAN -> "Likely a human voice"
    }

    private fun render(s: VoiceState.Snapshot) {
        listenMic.isEnabled = !s.listening || s.source != VoiceService.SOURCE_MIC
        listenPhone.isEnabled = !s.listening || s.source != VoiceService.SOURCE_PLAYBACK
        stop.visibility = if (s.listening) View.VISIBLE else View.GONE
        // Headline = the whole session so far (stable over a long speech); "right now" goes in the detail line.
        val p = s.smoothed
        val whole = s.summary.verdict
        when {
            whole != null -> {
                verdict.text = s.summary.headline()
                verdict.setTextColor(if (s.summary.partlyAi) Verdict.AI.color else whole.color)
            }
            p != null -> {
                verdict.text = verdictText(Verdict.voice(p))
                verdict.setTextColor(Verdict.voice(p).color)
            }
            else -> {
                verdict.text = if (s.listening) "Listening…" else "Not listening"
                verdict.setTextColor(INK)
            }
        }
        meter.progress = ((s.summary.median ?: p ?: 0f) * 100).toInt()
        sessionLine.text = if (s.summary.count + s.summary.skipped == 0) "" else
            listOfNotNull(s.summary.describe(), s.summary.describeStretches()).joinToString("\n")
        sessionLine.visibility = if (sessionLine.text.isEmpty()) View.GONE else View.VISIBLE
        // Music is noisy until a few windows are in, so hold the line back until 3 are scored.
        val m = s.musicMean
        liveMusic.text = if (m != null && s.musicWindows >= 3) MusicDetector.describe(m) else ""
        liveMusic.visibility = if (liveMusic.text.isEmpty()) View.GONE else View.VISIBLE
        timeline.show(s.summary.scores)
        detail.text = buildString {
            if (p != null) append("Right now: ${(p * 100).toInt()}% AI  ·  ")
            if (s.heardSec > 0) append("${Speech.clock(s.heardSec)} heard  ·  ")
            append(when {
                s.status.isEmpty() -> "Choose a source to start"
                !s.listening && s.status.startsWith("Listening") -> "Stopped"   // session ended normally
                else -> s.status
            })
        }
        transcript.text = (s.transcript + if (s.partial.isNotEmpty()) " " + s.partial + "…" else "").trim()
            .ifEmpty { if (s.listening) "…" else "The transcript will appear here." }
    }

    // ---------------- permissions & capture ----------------

    private fun withAudioPermission(action: () -> Unit) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) action() else {
            pending = action
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending?.invoke()
        else Toast.makeText(this, "Microphone permission is needed to listen.", Toast.LENGTH_LONG).show()
        pending = null
    }

    private fun askForPlaybackCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) VoiceService.startPlayback(this, resultCode, data)
        if (requestCode == REQ_FILE && resultCode == RESULT_OK) data?.data?.let {
            transcriptExpanded = false
            FileCheck.start(applicationContext, it)
        }
    }

    private fun imageServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = "$packageName/${ImageScanService::class.java.name}"
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }.any { it.equals(me, ignoreCase = true) }
    }

    // ---------------- tiny view helpers ----------------

    private fun px(v: Int) = (v * dp).toInt()

    private operator fun LinearLayout.plusAssign(v: View) = addView(v)

    private fun label(t: String, size: Float, color: Int, mono: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (mono) { typeface = Typeface.MONOSPACE; letterSpacing = 0.12f }
        setPadding(0, px(2), 0, px(2))
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 21f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        setTextColor(GOLD)
        setPadding(0, px(26), 0, px(6))
    }

    private fun card() = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = 10 * dp
        setStroke(px(1), LINE)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(INK)
        background = card()
        setPadding(px(14), 0, px(14), 0)
        setOnClickListener { onClick() }
    }

    private fun toggle(t: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        textSize = 15f
        setTextColor(INK)
        isChecked = on
        setPadding(0, px(8), 0, px(8))
        setOnCheckedChangeListener { _: CompoundButton, checked -> onChange(checked) }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_CAPTURE = 2
        private const val REQ_FILE = 3
        private const val TRANSCRIPT_PREVIEW = 800
        private const val INK = 0xFFEDE7DB.toInt()
        private const val MUTE = 0xFF8D867C.toInt()
        private const val GOLD = 0xFFC9A45C.toInt()
        private const val GREEN = 0xFF6FBF8A.toInt()
        private const val CARD = 0xFF171513.toInt()
        private const val LINE = 0xFF2E2A27.toInt()
    }
}
