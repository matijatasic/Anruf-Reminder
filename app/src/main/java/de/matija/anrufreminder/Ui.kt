package de.matija.anrufreminder

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Farben, Schriften und Bausteine im Stil von matija-tasic.de (Dunkelmodus). */
object Ui {
    const val BG = 0xFF1A1E1F.toInt()
    const val SURFACE = 0xFF212627.toInt()
    const val INK = 0xFFF3F4F4.toInt()
    const val TEXT = 0xFFD5D9D8.toInt()
    const val MUTED = 0xFF8E9695.toInt()
    const val LINE = 0xFF2D3334.toInt()
    const val LINE_STRONG = 0xFF3D4446.toInt()
    const val TEAL = 0xFF1FB5A8.toInt()
    const val TEAL_TEXT = 0xFF5FD3C7.toInt()
    const val TINT = 0xFF1B2F2D.toInt()
    const val TINT_B = 0xFF2A4A47.toInt()
    const val CHIP_BG = 0xFF2A3133.toInt()
    const val CHIP_TEXT = 0xFF7FE0D6.toInt()
    const val AMBER = 0xFFE3B04B.toInt()
    const val AMBER_T = 0xFF33291A.toInt()
    const val RED = 0xFFD64545.toInt()

    private var cache: Map<Int, Typeface> = emptyMap()

    private fun font(ctx: Context, id: Int): Typeface =
        cache[id] ?: ctx.resources.getFont(id).also { cache = cache + (id to it) }

    fun sans(ctx: Context) = font(ctx, R.font.inter_regular)
    fun semibold(ctx: Context) = font(ctx, R.font.inter_semibold)
    fun display(ctx: Context) = font(ctx, R.font.inter_display_bold)
    fun mono(ctx: Context) = font(ctx, R.font.mono_regular)
    fun monoBold(ctx: Context) = font(ctx, R.font.mono_bold)

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusPx
        if (stroke != null) setStroke(strokePx, stroke)
    }

    fun oval(fill: Int, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != null) setStroke(strokePx, stroke)
    }

    /** Hintergrund mit Berührungs-Effekt. */
    fun pressable(base: GradientDrawable, ripple: Int = 0x33FFFFFF) =
        RippleDrawable(ColorStateList.valueOf(ripple), base, null)

    /** Mono-Kapitelkopf: klein, Großbuchstaben, weit gesperrt (.lbl / .ch-title). */
    fun label(ctx: Context, text: String, color: Int = MUTED) = TextView(ctx).apply {
        this.text = text.uppercase()
        typeface = monoBold(ctx)
        textSize = 11f
        letterSpacing = 0.2f
        setTextColor(color)
    }

    fun title(ctx: Context, text: String, size: Float = 34f) = TextView(ctx).apply {
        this.text = text
        typeface = display(ctx)
        textSize = size
        letterSpacing = -0.02f
        setTextColor(INK)
    }

    fun body(ctx: Context, text: String, color: Int = TEXT, size: Float = 16f) = TextView(ctx).apply {
        this.text = text
        typeface = sans(ctx)
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.25f)
    }

    /** Petrol-Knopf wie .btn-primary. */
    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        typeface = semibold(ctx)
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        val p = dp(ctx, 15)
        setPadding(p, p, p, p)
        background = pressable(rounded(TEAL, dp(ctx, 8).toFloat()))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Hinweis-Knopf in Bernstein für fehlende Berechtigungen. */
    fun warningButton(ctx: Context, text: String, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = "⚠  $text"
        typeface = semibold(ctx)
        textSize = 14f
        setTextColor(AMBER)
        val p = dp(ctx, 12)
        setPadding(p + dp(ctx, 2), p, p, p)
        background = pressable(rounded(AMBER_T, dp(ctx, 8).toFloat(), AMBER, dp(ctx, 1)))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Kleiner Chip wie .kicker b (dunkler Hintergrund, Mint-Schrift). */
    fun chip(ctx: Context, text: String, fg: Int = CHIP_TEXT, bg: Int = CHIP_BG) = TextView(ctx).apply {
        this.text = text.uppercase()
        typeface = monoBold(ctx)
        textSize = 10f
        letterSpacing = 0.1f
        setTextColor(fg)
        setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5))
        background = rounded(bg, dp(ctx, 4).toFloat())
    }

    fun divider(ctx: Context) = View(ctx).apply { setBackgroundColor(LINE) }
}
