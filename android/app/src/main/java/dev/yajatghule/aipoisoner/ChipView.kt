package dev.yajatghule.aipoisoner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * The small floating chip, like the extension's badge: a dot in the worst colour on screen and the counts.
 * Tap it to minimise (only the dot and the number stay, the boxes on the images are hidden) or expand again.
 */
class ChipView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private var ai = 0
    private var unsure = 0
    private var human = 0
    var minimised = false
        set(v) { field = v; requestLayout(); invalidate() }

    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE60E0D0C.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * dp; color = 0x55C9A45C
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDE7DB.toInt(); textSize = 12 * dp; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        contentDescription = "SENTINEL: tap to minimise or expand"
        isClickable = true
    }

    /** Counts per verdict for the images now on screen; shown in the chip. */
    fun update(badges: List<OverlayView.Badge>) {
        ai = badges.count { Verdict.image(it.score) == Verdict.AI }
        unsure = badges.count { Verdict.image(it.score) == Verdict.UNSURE }
        human = badges.size - ai - unsure
        visibility = if (badges.isEmpty()) GONE else VISIBLE
        requestLayout()
        invalidate()
    }

    private fun worst() = if (ai > 0) Verdict.AI else if (unsure > 0) Verdict.UNSURE else Verdict.HUMAN

    /** "2 AI · 1 ?" when expanded; just the total when minimised. */
    private fun label(): String {
        if (minimised) return (ai + unsure + human).toString()
        val parts = ArrayList<String>()
        if (ai > 0) parts += "$ai AI"
        if (unsure > 0) parts += "$unsure ?"
        if (human > 0) parts += "$human OK"
        return parts.joinToString(" · ")
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = (if (minimised) 28 else 32) * dp
        val w = (if (minimised) 14 * dp + text.measureText(label()) + 20 * dp else 16 * dp + text.measureText(label()) + 26 * dp)
        setMeasuredDimension(w.toInt(), h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val r = RectF(1f, 1f, width - 1f, height - 1f)
        canvas.drawRoundRect(r, height / 2f, height / 2f, pill)
        canvas.drawRoundRect(r, height / 2f, height / 2f, ring)
        dot.color = worst().color
        val cx = (if (minimised) 14 else 17) * dp
        canvas.drawCircle(cx, height / 2f, (if (minimised) 5f else 5.5f) * dp, dot)
        canvas.drawText(label(), cx + 11 * dp, height / 2f + text.textSize * 0.36f, text)
    }
}
