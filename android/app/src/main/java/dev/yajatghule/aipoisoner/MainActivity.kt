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
import android.widget.Button
import android.widget.CompoundButton
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
    private lateinit var transcript: TextView
    private lateinit var listenMic: Button
    private lateinit var listenPhone: Button
    private lateinit var stop: Button
    private val modelRows = mutableMapOf<Models.Kind, Pair<TextView, Button>>()
    private var pending: (() -> Unit)? = null
    private val onVoice: (VoiceState.Snapshot) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        a11yStatus.text = if (imageServiceOn()) "● On — labels appear on images in other apps"
        else "○ Off — turn on “AI Poisoner image labels” in Accessibility settings"
        a11yStatus.setTextColor(if (imageServiceOn()) GREEN else MUTE)
        Models.Kind.entries.forEach { refreshModelRow(it) }
        VoiceState.observe(onVoice)
    }

    override fun onPause() {
        VoiceState.remove(onVoice)
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
        col += label("Is this voice real? Listens in 4-second windows and transcribes as it goes.", 13f, MUTE)
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

        return ScrollView(this).apply { addView(col) }
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

    private fun refreshModelRow(kind: Models.Kind, progress: Float? = null) {
        val (text, get) = modelRows[kind] ?: return
        val ready = Models.isReady(this, kind)
        text.text = when {
            ready -> "${kind.label}  ✓"
            progress != null -> "${kind.label} · ${(progress * 100).toInt()}%"
            else -> "${kind.label} · ${kind.approxMb} MB"
        }
        get.visibility = if (ready) View.GONE else View.VISIBLE
        get.isEnabled = progress == null
    }

    private fun download(kind: Models.Kind) {
        refreshModelRow(kind, 0f)
        Thread {
            val error = runCatching { Models.download(this, kind) { p -> runOnUiThread { refreshModelRow(kind, p) } } }.exceptionOrNull()
            runOnUiThread {
                refreshModelRow(kind)
                if (error != null) Toast.makeText(this, "${kind.label}: ${error.message}", Toast.LENGTH_LONG).show()
                else if (kind == Models.Kind.IMAGE) ImageScanService.instance?.refresh()
            }
        }.start()
    }

    private fun render(s: VoiceState.Snapshot) {
        listenMic.isEnabled = !s.listening || s.source != VoiceService.SOURCE_MIC
        listenPhone.isEnabled = !s.listening || s.source != VoiceService.SOURCE_PLAYBACK
        stop.visibility = if (s.listening) View.VISIBLE else View.GONE
        val p = s.smoothed
        if (p == null) {
            verdict.text = if (s.listening) "Listening…" else "Not listening"
            verdict.setTextColor(INK)
            meter.progress = 0
        } else {
            val v = Verdict.voice(p)
            verdict.text = when (v) {
                Verdict.AI -> "Likely an AI voice"
                Verdict.UNSURE -> "Unsure"
                Verdict.HUMAN -> "Likely a human voice"
            }
            verdict.setTextColor(v.color)
            meter.progress = (p * 100).toInt()
        }
        detail.text = buildString {
            if (p != null) append("${(p * 100).toInt()}% AI (smoothed over ${s.windows} windows)  ·  ")
            append(s.status.ifEmpty { "Choose a source to start" })
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
        private const val INK = 0xFFEDE7DB.toInt()
        private const val MUTE = 0xFF8D867C.toInt()
        private const val GOLD = 0xFFC9A45C.toInt()
        private const val GREEN = 0xFF6FBF8A.toInt()
        private const val CARD = 0xFF171513.toInt()
        private const val LINE = 0xFF2E2A27.toInt()
    }
}
