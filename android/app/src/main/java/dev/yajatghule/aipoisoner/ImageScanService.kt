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
import kotlin.math.roundToInt

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
    @Volatile private var destroyed = false
    private var lastShot = 0L
    private var dirty = true
    /** Bumped whenever content moves; a scan that started before that holds boxes for the old layout. */
    @Volatile private var epoch = 0
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
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { epoch++; overlay?.clear(); dirty = true }
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
        if (destroyed) return
        lastShot = SystemClock.uptimeMillis()
        val startEpoch = epoch
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(shot: ScreenshotResult) {
                    val frame = try {
                        val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                        hw?.copy(Bitmap.Config.ARGB_8888, false).also { hw?.recycle() }
                    } catch (e: Throwable) {
                        Log.w(TAG, "screenshot copy failed", e)
                        null
                    } finally {
                        shot.hardwareBuffer.close()
                    }
                    if (destroyed) { frame?.recycle(); return }
                    overlay?.visibility = View.VISIBLE
                    if (frame == null) { Log.w(TAG, "could not read screenshot pixels"); scanning = false; return }
                    // The service can be torn down while a shot is in flight; execute() would then throw.
                    val queued = runCatching {
                        worker.execute {
                            var badges = emptyList<OverlayView.Badge>()
                            try {
                                // One bad crop (OOM, odd bounds) shouldn't hide the other badges.
                                badges = boxes.mapNotNull { box -> runCatching { scoreBox(frame, box, det) }.getOrNull() }
                                Log.d(TAG, "frame ${frame.width}x${frame.height}: " + badges.joinToString { "%.2f@%s".format(it.score, it.bounds.toShortString()) })
                            } finally {
                                frame.recycle()
                                main.post {
                                    if (!destroyed) {
                                        if (epoch == startEpoch) { overlay?.show(badges); shownBoxes = boxes }
                                        else { dirty = true; main.removeCallbacks(scanTask); main.postDelayed(scanTask, SETTLE_MS) }   // scrolled meanwhile: boxes are stale
                                    }
                                    scanning = false
                                }
                            }
                        }
                    }.isSuccess
                    if (!queued) { frame.recycle(); scanning = false }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed: $errorCode")
                    if (destroyed) return
                    overlay?.visibility = View.VISIBLE
                    scanning = false
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) main.postDelayed(scanTask, SCREENSHOT_GAP_MS)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot threw", e)
            overlay?.visibility = View.VISIBLE
            scanning = false
        }
    }

    private fun scoreBox(frame: Bitmap, box: Rect, det: ImageDetector): OverlayView.Badge? {
        val r = Rect(box)
        if (!r.intersect(0, 0, frame.width, frame.height) || r.width() < 64 || r.height() < 64) return null
        val crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        val score = try {
            val key = cacheKey(crop)
            key?.let { cache.get(it) } ?: runCatching { det.score(crop) }
                .onFailure { Log.e(TAG, "scoring failed", it) }.getOrNull()?.also { if (key != null) cache.put(key, it) }
        } finally {
            crop.recycle()
        }
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

    /**
     * Cache key: 64-bit difference hash mixed with a coarse mean colour and the aspect ratio, so layouts
     * that only share a gradient shape don't collide. Null (don't cache) for flat or near-uniform images,
     * where the hash carries no information.
     */
    private fun cacheKey(bmp: Bitmap): Long? {
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        try {
            var h = 0L
            var sr = 0; var sg = 0; var sb = 0
            var sum = 0.0; var sumSq = 0.0
            for (y in 0 until 8) for (x in 0 until 9) {
                val a = small.getPixel(x, y)
                val r = (a shr 16) and 255; val g = (a shr 8) and 255; val b = a and 255
                sr += r; sg += g; sb += b
                val la = r * 3 + g * 6 + b
                val l = la / 10.0
                sum += l; sumSq += l * l
                if (x < 8) {
                    val c = small.getPixel(x + 1, y)
                    val lb = ((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)
                    h = (h shl 1) or (if (la > lb) 1L else 0L)
                }
            }
            val mean = sum / 72
            if (h == 0L || sumSq / 72 - mean * mean < 25.0) return null   // under ~5 levels of spread: effectively flat
            val colour = ((sr / 72) shr 4 shl 8) or ((sg / 72) shr 4 shl 4) or ((sb / 72) shr 4)
            val aspect = (Math.log(bmp.width.toDouble() / bmp.height) / Math.log(2.0) * 4).roundToInt().coerceIn(-15, 15) + 16
            val extra = (colour.toLong() shl 5) or aspect.toLong()
            return h xor (extra * -7046029254386353131L)
        } finally {
            small.recycle()
        }
    }

    /** Called by MainActivity after the image model finishes downloading, or a setting changes. */
    fun refresh() {
        loadDetector()
        main.post { dirty = true; overlay?.invalidate(); scan() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        destroyed = true
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
