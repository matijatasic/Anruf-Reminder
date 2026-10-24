package de.matija.anrufreminder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scroll-Rad für Zahlen: die gewählte Zahl steht groß zwischen zwei Petrol-Linien,
 * die Nachbarn werden kleiner und blasser. Wischen und Schwung rasten auf ganze Werte ein.
 */
class WheelPicker(ctx: Context, private val min: Int, private val max: Int, initial: Int, private val unit: String) : View(ctx) {

    private val rowH = Ui.dp(ctx, 44).toFloat()
    private val visibleRows = 5
    private var offset = (initial.coerceIn(min, max) - min) * rowH
    private val scroller = OverScroller(ctx)
    private var tracker: VelocityTracker? = null
    private var lastY = 0f
    private var lastValue = initial

    val value: Int get() = (min + (offset / rowH).roundToInt()).coerceIn(min, max)

    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.semibold(ctx)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.mono(ctx)
        textSize = Ui.dp(ctx, 12).toFloat()
        letterSpacing = 0.1f
        color = Ui.TEAL_TEXT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEAL
        strokeWidth = Ui.dp(ctx, 1).toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rowH * visibleRows).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val first = ((offset / rowH).toInt() - visibleRows / 2 - 1).coerceAtLeast(0)
        val last = ((offset / rowH).toInt() + visibleRows / 2 + 1).coerceAtMost(max - min)
        for (i in first..last) {
            val y = cy + i * rowH - offset
            val d = (abs(y - cy) / rowH).coerceAtMost(2.5f)
            numPaint.textSize = Ui.dp(context, 34) * (1f - 0.22f * d).coerceAtLeast(0.45f)
            numPaint.color = if (d < 0.5f) Ui.INK else Ui.MUTED
            numPaint.alpha = (255 * (1f - 0.35f * d).coerceAtLeast(0.15f)).toInt()
            val baseline = y - (numPaint.descent() + numPaint.ascent()) / 2
            canvas.drawText((min + i).toString(), cx, baseline, numPaint)
        }
        val left = width * 0.14f
        val right = width * 0.86f
        canvas.drawLine(left, cy - rowH / 2, right, cy - rowH / 2, linePaint)
        canvas.drawLine(left, cy + rowH / 2, right, cy + rowH / 2, linePaint)
        canvas.drawText(unit, cx + Ui.dp(context, 52), cy - (unitPaint.descent() + unitPaint.ascent()) / 2, unitPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                scrollBy(lastY - e.y)
                lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                if (abs(vy) > 300) {
                    scroller.fling(0, offset.toInt(), 0, -vy.toInt(), 0, 0, 0, ((max - min) * rowH).toInt())
                    postInvalidateOnAnimation()
                } else snap()
            }
        }
        return true
    }

    private fun scrollBy(dy: Float) {
        offset = (offset + dy).coerceIn(0f, (max - min) * rowH)
        tick()
        invalidate()
    }

    private fun snap() {
        val target = ((offset / rowH).roundToInt() * rowH).toInt()
        scroller.startScroll(0, offset.toInt(), 0, target - offset.toInt(), 180)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currY.toFloat().coerceIn(0f, (max - min) * rowH)
            tick()
            if (scroller.isFinished && offset % rowH != 0f) snap() else postInvalidateOnAnimation()
        }
    }

    /** Leichtes Rasten spürbar machen, wenn eine neue Zahl in die Mitte rückt. */
    private fun tick() {
        val v = value
        if (v != lastValue) {
            lastValue = v
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}
