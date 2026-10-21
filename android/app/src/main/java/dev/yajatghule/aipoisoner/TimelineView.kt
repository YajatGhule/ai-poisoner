package dev.yajatghule.aipoisoner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * A strip of bars, one per scored window (or per group of windows when there are more than fit),
 * coloured by verdict. Lets you see *where* in a long speech the AI-sounding parts are.
 */
class TimelineView(context: Context) : View(context) {
    private var scores: List<Float> = emptyList()
    private val dp = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = Paint().apply { color = 0xFF2E2A27.toInt() }

    fun show(next: List<Float>) {
        if (next == scores) return
        scores = next
        visibility = if (next.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (36 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        canvas.drawRect(0f, h - 1 * dp, width.toFloat(), h, base)
        if (scores.isEmpty()) return
        val slot = 4 * dp                                   // bar + gap
        val bars = minOf(scores.size, (width / slot).toInt().coerceAtLeast(1))
        val per = scores.size.toFloat() / bars              // windows per bar when the speech is long
        val w = width.toFloat() / bars
        for (i in 0 until bars) {
            val from = (i * per).toInt()
            val to = maxOf(from + 1, ((i + 1) * per).toInt()).coerceAtMost(scores.size)
            val p = scores.subList(from, to).max()          // a bar is as AI as its most AI-sounding window
            bar.color = Verdict.voice(p).color
            val top = h - 1 * dp - (h - 2 * dp) * (0.15f + 0.85f * p)
            canvas.drawRect(i * w + w * 0.15f, top, (i + 1) * w - w * 0.15f, h - 1 * dp, bar)
        }
    }
}
