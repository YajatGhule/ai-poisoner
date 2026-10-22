package dev.yajatghule.aipoisoner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Full-screen, non-touchable layer drawn by the accessibility service.
 * Badges are positioned in screen coordinates (the same space as node bounds and screenshots).
 */
class OverlayView(context: Context) : View(context) {
    data class Badge(val bounds: Rect, val score: Float)

    private var badges: List<Badge> = emptyList()
    private val dp = resources.displayMetrics.density
    private val loc = IntArray(2)

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD60E0D0C.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDE7DB.toInt()
        textSize = 12 * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val cover = Paint().apply { color = 0xFF171513.toInt() }
    private val coverText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8D867C.toInt()
        textSize = 13 * dp
        textAlign = Paint.Align.CENTER
    }
    private val boxShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * dp
        color = 0x66000000
    }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
    }

    fun show(next: List<Badge>) {
        badges = next
        invalidate()
    }

    fun clear() {
        if (badges.isNotEmpty()) show(emptyList())
    }

    override fun onDraw(canvas: Canvas) {
        if (badges.isEmpty()) return
        // Convert screen coordinates to this window's coordinates.
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val coverAi = Prefs.coverAi(context)
        for (b in badges) {
            val v = Verdict.image(b.score)
            if (coverAi && v == Verdict.AI) {
                canvas.drawRect(b.bounds, cover)
                canvas.drawText("AI image hidden", b.bounds.exactCenterX(), b.bounds.exactCenterY(), coverText)
            }
            // Outline first so the pill draws on top of it.
            val r = 10 * dp
            val outline = RectF(b.bounds).apply { inset(box.strokeWidth / 2, box.strokeWidth / 2) }
            canvas.drawRoundRect(outline, r, r, boxShadow)
            box.color = if (v == Verdict.HUMAN) (v.color and 0xFFFFFF) or (0x8C shl 24) else v.color
            canvas.drawRoundRect(outline, r, r, box)
            val label = if (v == Verdict.HUMAN) v.label else "${v.label} ${(b.score * 100).toInt()}%"
            val pad = 8 * dp
            val w = text.measureText(label) + 26 * dp
            val h = 24 * dp
            val left = b.bounds.left + pad
            val top = b.bounds.top + pad
            canvas.drawRoundRect(RectF(left, top, left + w, top + h), h / 2, h / 2, pill)
            dot.color = v.color
            canvas.drawCircle(left + 11 * dp, top + h / 2, 3.5f * dp, dot)
            canvas.drawText(label, left + 19 * dp, top + h / 2 + text.textSize * 0.36f, text)
        }
        canvas.restore()
    }
}
