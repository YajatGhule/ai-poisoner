package dev.yajatghule.aipoisoner

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * Capture -> Processing -> Overlay, for images in any app:
 * find image nodes on screen, screenshot just those boxes, score them on-device, draw badges on top.
 */
class ImageScanService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = LruCache<Long, Float>(500)      // perceptual hash -> score, so scrolling back is instant
    @Volatile private var detector: ImageDetector? = null
    private var overlay: OverlayView? = null
    private var scanning = false
    private var lastShot = 0L
    private var dirty = true
    private var shownBoxes: List<Rect> = emptyList()
    private val scanTask = Runnable { scan() }

    override fun onServiceConnected() {
        val view = OverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
        instance = this
        loadDetector()
    }

    private fun loadDetector() {
        if (detector != null || !Models.isReady(this, Models.Kind.IMAGE)) return
        worker.execute {
            if (detector == null) detector = runCatching { ImageDetector(Models.file(this, Models.Kind.IMAGE)) }
                .onFailure { Log.e(TAG, "image model failed to load", it) }.getOrNull()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return
        when (event.eventType) {
            // Content moved: old badges would point at the wrong place.
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { overlay?.clear(); dirty = true }
        }
        main.removeCallbacks(scanTask)
        main.postDelayed(scanTask, SETTLE_MS)
    }

    private fun scan() {
        val view = overlay ?: return
        if (!Prefs.imagesEnabled(this)) { view.clear(); return }
        val det = detector ?: run { loadDetector(); return }
        if (scanning) { main.postDelayed(scanTask, 300); return }
        val root = rootInActiveWindow ?: return
        if (root.packageName == packageName) { view.clear(); return }

        val minPx = (MIN_DP * resources.displayMetrics.density).toInt()
        val boxes = ArrayList<Rect>()
        collectImages(root, boxes, minPx, 0)
        if (boxes.isEmpty()) { view.clear(); return }
        // Pages fire content-changed events constantly. If nothing moved, don't screenshot again
        // (but still recheck every few seconds, for carousels that swap pictures in place).
        if (!dirty && boxes == shownBoxes && SystemClock.uptimeMillis() - lastShot < RECHECK_MS) return
        Log.d(TAG, "scan ${root.packageName}: ${boxes.size} images")

        // takeScreenshot() is rate-limited (~3/s); wait out the gap instead of failing.
        val wait = SCREENSHOT_GAP_MS - (SystemClock.uptimeMillis() - lastShot)
        if (wait > 0) { main.postDelayed(scanTask, wait); return }

        scanning = true
        dirty = false
        view.visibility = View.INVISIBLE       // keep our own badges out of the screenshot
        main.postDelayed({ capture(boxes.take(MAX_PER_SCAN), det) }, 48)
    }

    private fun capture(boxes: List<Rect>, det: ImageDetector) {
        lastShot = SystemClock.uptimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                overlay?.visibility = View.VISIBLE
                val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                val frame = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                shot.hardwareBuffer.close()
                if (frame == null) { Log.w(TAG, "could not read screenshot pixels"); scanning = false; return }
                worker.execute {
                    val badges = boxes.mapNotNull { box -> scoreBox(frame, box, det) }
                    Log.d(TAG, "frame ${frame.width}x${frame.height}: " + badges.joinToString { "%.2f@%s".format(it.score, it.bounds.toShortString()) })
                    frame.recycle()
                    main.post {
                        overlay?.show(badges)
                        shownBoxes = boxes
                        scanning = false
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot failed: $errorCode")
                overlay?.visibility = View.VISIBLE
                scanning = false
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) main.postDelayed(scanTask, SCREENSHOT_GAP_MS)
            }
        })
    }

    private fun scoreBox(frame: Bitmap, box: Rect, det: ImageDetector): OverlayView.Badge? {
        val r = Rect(box)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.width() < 64 || r.height() < 64) return null
        val crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        val key = dHash(crop)
        val score = cache.get(key) ?: runCatching { det.score(crop) }
            .onFailure { Log.e(TAG, "scoring failed", it) }.getOrNull()?.also { cache.put(key, it) }
        crop.recycle()
        return score?.let { OverlayView.Badge(r, it) }
    }

    private fun collectImages(node: AccessibilityNodeInfo, out: MutableList<Rect>, minPx: Int, depth: Int) {
        if (depth > 60 || !node.isVisibleToUser) return
        val cls = node.className?.toString()
        // Views, Compose (role Image) and WebView <img> all surface as one of these.
        if (cls == "android.widget.ImageView" || cls == "android.widget.Image") {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() >= minPx && r.height() >= minPx) out += r
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectImages(it, out, minPx, depth + 1) }
    }

    /** 64-bit difference hash: same picture at a different scroll position hits the cache. */
    private fun dHash(bmp: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val a = small.getPixel(x, y)
            val b = small.getPixel(x + 1, y)
            val la = ((a shr 16) and 255) * 3 + ((a shr 8) and 255) * 6 + (a and 255)
            val lb = ((b shr 16) and 255) * 3 + ((b shr 8) and 255) * 6 + (b and 255)
            h = (h shl 1) or (if (la > lb) 1L else 0L)
        }
        small.recycle()
        return h
    }

    /** Called by MainActivity after the image model finishes downloading, or a setting changes. */
    fun refresh() {
        loadDetector()
        main.post { dirty = true; overlay?.invalidate(); scan() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        worker.execute { detector?.close(); detector = null }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val SETTLE_MS = 600L
        private const val SCREENSHOT_GAP_MS = 350L
        private const val RECHECK_MS = 5000L
        private const val MIN_DP = 96
        private const val MAX_PER_SCAN = 8
        private const val TAG = "AiPoisoner"

        @Volatile var instance: ImageScanService? = null
            private set
    }
}
