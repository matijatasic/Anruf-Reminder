package de.matija.anrufreminder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Grafik im Kopf der Startseite.
 * Ohne geplanten Anruf: Telefon-Emblem mit leuchtenden Puls-Ringen (wie das App-Icon).
 * Mit geplantem Anruf: Zifferblatt mit Countdown bis zum nächsten Anruf.
 */
class HeaderEmblem(ctx: Context) : View(ctx) {

    private companion object {
        // Gemeinsame Maße beider Ansichten (Anteil am Radius), damit Telefon und Countdown gleich groß wirken.
        const val RING = 0.58f
        const val MID = 0.70f
        const val OUTER = 0.80f
        const val RING_W = 3.5f
    }

    private var remainingMs: Long? = null
    private var progress = 0f
    private var pulse = 0f

    private val d = ctx.resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.semibold(ctx)
        color = Ui.INK
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.mono(ctx)
        color = Ui.TEAL_TEXT
        letterSpacing = 0.12f
        textAlign = Paint.Align.CENTER
    }
    private val phone = ctx.getDrawable(R.drawable.ic_call)!!.mutate().apply { setTint(0xFFFFFFFF.toInt()) }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    /** [remaining] = Zeit bis zum nächsten Anruf, null = keiner geplant; [fraction] = bereits verstrichener Anteil. */
    fun show(remaining: Long?, fraction: Float) {
        remainingMs = remaining
        progress = fraction.coerceIn(0f, 1f)
        if (remaining == null) { if (isAttachedToWindow && !animator.isStarted) animator.start() }
        else animator.cancel()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (remainingMs == null) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (150 * d).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (remainingMs == null) drawPhone(canvas, cx, cy, r) else drawCountdown(canvas, cx, cy, r)
    }

    private fun drawPhone(c: Canvas, cx: Float, cy: Float, r: Float) {
        // Weiches Leuchten hinter dem Emblem.
        fill.shader = RadialGradient(cx, cy, r * 0.8f, 0x471FB5A8, 0x001FB5A8, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.8f, fill)
        fill.shader = null

        stroke.color = Ui.TEAL
        stroke.strokeWidth = 0.8f * d
        stroke.alpha = 46
        c.drawCircle(cx, cy, r * OUTER, stroke)
        stroke.strokeWidth = 1.2f * d
        stroke.alpha = 90
        c.drawCircle(cx, cy, r * MID, stroke)

        // Ein Ring läuft langsam nach außen und verblasst, wie ein Klingeln.
        stroke.strokeWidth = 1.5f * d
        stroke.alpha = (110 * (1f - pulse)).toInt()
        c.drawCircle(cx, cy, r * (RING + (OUTER + 0.08f - RING) * pulse), stroke)

        val core = r * RING
        fill.color = 0xFF1D2526.toInt()
        c.drawCircle(cx, cy, core, fill)
        stroke.alpha = 255
        stroke.strokeWidth = RING_W * d
        c.drawCircle(cx, cy, core, stroke)

        val ic = (core * 0.95f).toInt()
        phone.setBounds((cx - ic / 2).toInt(), (cy - ic / 2).toInt(), (cx + ic / 2).toInt(), (cy + ic / 2).toInt())
        phone.draw(c)
    }

    private fun drawCountdown(c: Canvas, cx: Float, cy: Float, r: Float) {
        // 60 Minutenstriche, jeder fünfte länger und in Petrol.
        val outer = r * OUTER
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val major = i % 5 == 0
            val inner = outer - (if (major) (OUTER - MID) * r * 0.9f else (OUTER - MID) * r * 0.5f)
            stroke.color = if (major) Ui.TEAL_TEXT else Ui.LINE_STRONG
            stroke.strokeWidth = (if (major) 1.6f else 1f) * d
            c.drawLine(
                cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(),
                cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat(), stroke
            )
        }
        val ring = r * RING
        val box = RectF(cx - ring, cy - ring, cx + ring, cy + ring)
        stroke.strokeWidth = RING_W * d
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Ui.LINE
        c.drawCircle(cx, cy, ring, stroke)
        stroke.color = Ui.TEAL
        c.drawArc(box, -90f, 360f * progress.coerceAtLeast(0.01f), false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        // Text mit Einheiten; wird bei Bedarf verkleinert, damit er in den Ring passt.
        val t = timeText(remainingMs ?: 0L)
        timePaint.textSize = ring * 0.36f
        val maxW = ring * 1.5f
        val w = timePaint.measureText(t)
        if (w > maxW) timePaint.textSize *= maxW / w
        c.drawText(t, cx, cy + ring * 0.08f, timePaint)
        labelPaint.textSize = ring * 0.13f
        c.drawText("BIS ZUM ANRUF", cx, cy + ring * 0.36f, labelPaint)
    }

    /** Immer mit Einheiten, damit es nicht wie eine Uhrzeit aussieht: "5 min", "13 h 54 min", "2 T 3 h". */
    private fun timeText(ms: Long): String {
        val min = (ms + 59_999L) / 60_000L
        if (min >= 24 * 60) return "${min / (24 * 60)} T ${(min % (24 * 60)) / 60} h"
        if (min >= 60) return "${min / 60} h ${min % 60} min"
        return "$min min"
    }
}
