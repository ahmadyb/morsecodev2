package com.morsecode.app.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.morsecode.app.R
import com.morsecode.app.core.util.D
import com.morsecode.app.util.ThemeColors

/**
 * The MorseCode widget kit: rounded cards, pills, gradient buttons, avatars, stat cells and the
 * Sunflower progress bar. Everything is built from plain Android views - no XML inflation and no
 * UI framework - so a 2016 phone lays screens out in a few milliseconds.
 */
object W {

    val MONO: Typeface = Typeface.MONOSPACE
    val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    fun context(view: View): Context = view.context

    // ------------------------------------------------------------ primitives

    fun label(
        ctx: Context,
        text: CharSequence,
        sizeSp: Float = 14f,
        color: Int = 0,
        bold: Boolean = false,
        mono: Boolean = false,
        gravity: Int = Gravity.START
    ): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(sizeSp)
        t.setTextColor(if (color == 0) ThemeColors.text(ctx) else color)
        if (bold) t.typeface = BOLD
        if (mono) t.typeface = MONO
        t.setGravity(gravity)
        t.maxLines = 6
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    fun icon(ctx: Context, resId: Int, sizeDp: Int = 22, tint: Int = 0): ImageView {
        val v = ImageView(ctx)
        v.setImageResource(resId)
        v.setColorFilter(if (tint == 0) ThemeColors.text2(ctx) else tint)
        val size = D.dp(ctx, sizeDp.toFloat())
        v.layoutParams = LinearLayout.LayoutParams(size, size)
        val p = D.dp(ctx, 2f)
        v.setPadding(p, p, p, p)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        return v
    }

    /** Recolours the icon inside a composite widget (iconButton returns a FrameLayout). */
    fun tint(view: View, color: Int) {
        if (view is ImageView) {
            view.setColorFilter(color)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is ImageView) {
                    child.setColorFilter(color)
                    return
                }
            }
        }
    }

    fun spacer(ctx: Context, weight: Float = 1f): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(0, 0, weight)
        return v
    }

    fun gap(ctx: Context, dp: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, D.dp(ctx, dp.toFloat()))
        return v
    }

    fun hgap(ctx: Context, dp: Int): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(D.dp(ctx, dp.toFloat()), 1)
        return v
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(ThemeColors.line(ctx))
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, D.dp(ctx, 1f))
        return v
    }

    fun row(ctx: Context, padV: Int = 0, padH: Int = 0): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.setGravity(Gravity.CENTER_VERTICAL)
        if (padV > 0 || padH > 0) l.pad(padH, padV, padH, padV)
        l.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return l
    }

    fun column(ctx: Context, padV: Int = 0, padH: Int = 0): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        if (padV > 0 || padH > 0) l.pad(padH, padV, padH, padV)
        l.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return l
    }

    // ------------------------------------------------------------ containers

    /** A rounded card surface (dark: #1A1A1A, light: white with a soft drop shadow). */
    fun card(ctx: Context, level: Int = 0, radiusDp: Float = 18f, padDp: Int = 14): LinearLayout {
        val c = column(ctx)
        c.background = ThemeColors.cardDrawable(ctx, level, radiusDp)
        c.pad(padDp, padDp, padDp, padDp)
        if (!ThemeColors.isDark(ctx)) {
            c.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            c.elevation = D.dp(ctx, 2f).toFloat()
        }
        return c
    }

    /**
     * The two/three-way pill switcher used by Files (type tabs) and History
     * (Received / Sent). The active segment is a filled accent pill.
     */
    fun segmented(ctx: Context, labels: List<String>, active: Int, onSelect: (Int) -> Unit): LinearLayout {
        val bar = row(ctx)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(999f * ctx.resources.displayMetrics.density)
        d.setColor(if (ThemeColors.isDark(ctx)) ThemeColors.CardDark2 else ThemeColors.CardLight2)
        bar.background = d
        bar.pad(4, 4, 4, 4)
        for (i in labels.indices) {
            val t = label(ctx, labels[i], 13f,
                if (i == active) accentInk(ctx) else ThemeColors.text2(ctx), bold = true)
            t.setGravity(Gravity.CENTER)
            t.pad(10, 8, 10, 8)
            t.background = if (i == active) ThemeColors.accentDrawable(ctx) else null
            t.isClickable = true
            val index = i
            t.onClick { onSelect(index) }
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    /** Yellow switch row matching the settings mocks. */
    fun switchRow(ctx: Context, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = android.widget.Switch(ctx)
        sw.isChecked = checked
        sw.onToggle { onChange(it) }
        return settingRow(ctx, title, subtitle, sw, null)
    }

    fun outlinedCard(ctx: Context, accent: Boolean = true, radiusDp: Float = 18f, padDp: Int = 14): LinearLayout {
        val c = card(ctx, 0, radiusDp, padDp)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(D.dp(ctx, radiusDp).toFloat())
        d.setColor(if (accent) ThemeColors.accentSoft(ctx) else ThemeColors.card(ctx))
        d.setStroke(D.dp(ctx, 1f), ThemeColors.accentLine(ctx))
        c.background = d
        return c
    }

    fun pill(ctx: Context, text: CharSequence, color: Int, filled: Boolean = false, sizeSp: Float = 10f): TextView {
        val t = label(ctx, text, sizeSp, if (filled) accentInk(ctx) else color, bold = true, mono = true)
        t.setPadding(D.dp(ctx, 9f), D.dp(ctx, 3f), D.dp(ctx, 9f), D.dp(ctx, 3f))
        t.background = if (filled) {
            val d = GradientDrawable()
            d.setShape(GradientDrawable.RECTANGLE)
            d.setCornerRadius(999f * ctx.resources.displayMetrics.density)
            d.setColor(color)
            d
        } else ThemeColors.tagDrawable(ctx, color)
        return t
    }

    fun accentInk(ctx: Context): Int = ThemeColors.accentInk(ctx)

    // ------------------------------------------------------------ buttons

    /** Filled accent button - the single most important call to action on a screen. */
    fun accentButton(ctx: Context, text: CharSequence, iconRes: Int = 0): LinearLayout {
        val b = row(ctx)
        b.setGravity(Gravity.CENTER)
        b.background = ThemeColors.accentDrawable(ctx)
        b.isClickable = true
        b.isFocusable = true
        b.pad(16, 13, 16, 13)
        if (iconRes != 0) {
            b.addView(icon(ctx, iconRes, 18, ThemeColors.accentInk(ctx)))
            b.addView(hgap(ctx, 8))
        }
        b.addView(label(ctx, text, 15f, ThemeColors.accentInk(ctx), bold = true))
        return b
    }

    /** Neutral / outline button. */
    fun outlineButton(ctx: Context, text: CharSequence, iconRes: Int = 0, destructive: Boolean = false): LinearLayout {
        val b = row(ctx)
        b.setGravity(Gravity.CENTER)
        b.background = if (destructive) ThemeColors.dangerDrawable(ctx) else ThemeColors.neutralDrawable(ctx, 999f, true)
        b.isClickable = true
        b.isFocusable = true
        b.pad(16, 12, 16, 12)
        val color = if (destructive) ThemeColors.Red else ThemeColors.text(ctx)
        if (iconRes != 0) {
            b.addView(icon(ctx, iconRes, 18, color))
            b.addView(hgap(ctx, 8))
        }
        b.addView(label(ctx, text, 15f, color, bold = true))
        return b
    }

    fun successButton(ctx: Context, text: CharSequence): LinearLayout {
        val b = row(ctx)
        b.setGravity(Gravity.CENTER)
        b.background = ThemeColors.successDrawable(ctx)
        b.isClickable = true
        b.pad(16, 12, 16, 12)
        b.addView(label(ctx, text, 15f, Color.WHITE, bold = true))
        return b
    }

    /** Square icon-only button. */
    fun iconButton(ctx: Context, iconRes: Int, sizeDp: Int = 40, tint: Int = 0, filled: Boolean = true): FrameLayout {
        val f = FrameLayout(ctx)
        val size = D.dp(ctx, sizeDp.toFloat())
        f.layoutParams = LinearLayout.LayoutParams(size, size)
        f.background = ThemeColors.neutralDrawable(ctx, 999f, filled)
        f.isClickable = true
        val iv = icon(ctx, iconRes, (sizeDp * 0.5f).toInt(), if (tint == 0) ThemeColors.text(ctx) else tint)
        val lp = FrameLayout.LayoutParams(
            D.dp(ctx, (sizeDp * 0.5f).toFloat()), D.dp(ctx, (sizeDp * 0.5f).toFloat()), Gravity.CENTER
        )
        f.addView(iv, lp)
        return f
    }

    // ------------------------------------------------------------ avatars

    /** Peer avatar: coloured circle with the device initial. Colour comes from AvatarColors. */
    fun avatar(ctx: Context, initial: String, accent: ThemeColors.Accent, sizeDp: Int = 44): TextView {
        val t = label(ctx, initial.uppercase(), (sizeDp * 0.40f), accentInkFor(ctx, accent), bold = true, gravity = Gravity.CENTER)
        val d = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent.darkStart, accent.darkEnd))
        d.setShape(GradientDrawable.OVAL)
        t.background = d
        val size = D.dp(ctx, sizeDp.toFloat())
        t.layoutParams = LinearLayout.LayoutParams(size, size)
        t.setGravity(Gravity.CENTER)
        return t
    }

    /** The user's own avatar always uses the selected accent. */
    fun selfAvatar(ctx: Context, initial: String, sizeDp: Int = 44): TextView =
        avatar(ctx, initial, ThemeColors.accent(ctx), sizeDp)

    private fun accentInkFor(ctx: Context, accent: ThemeColors.Accent): Int {
        val luminance = (
            0.299 * Color.red(accent.darkStart) +
                0.587 * Color.green(accent.darkStart) +
                0.114 * Color.blue(accent.darkStart)
            )
        return if (luminance > 150) Color.parseColor("#1A1002") else Color.WHITE
    }

    // ------------------------------------------------------------ stat cells

    fun statCell(ctx: Context, value: String, captionRes: Int, tint: Int = 0): LinearLayout {
        val c = card(ctx, 1, 14f, 10)
        c.setGravity(Gravity.CENTER)
        val v = label(ctx, value, 18f, if (tint == 0) ThemeColors.accentStart(ctx) else tint, bold = true, gravity = Gravity.CENTER)
        val cap = label(ctx, ctx.getString(captionRes), 9f, ThemeColors.muted(ctx), bold = true, mono = true, gravity = Gravity.CENTER)
        c.addView(v)
        c.addView(cap)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(D.dp(ctx, 4f), 0, D.dp(ctx, 4f), 0)
        c.layoutParams = lp
        return c
    }

    fun infoRow(ctx: Context, key: String, value: String, valueColor: Int = 0): LinearLayout {
        val r = row(ctx, 0, 0)
        r.pad(0, 8, 0, 8)
        r.addView(label(ctx, key, 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        r.addView(spacer(ctx))
        r.addView(label(ctx, value, 13f, if (valueColor == 0) ThemeColors.text(ctx) else valueColor, bold = true, mono = true))
        return r
    }

    fun settingRow(
        ctx: Context,
        title: String,
        subtitle: String?,
        trailing: View?,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val r = row(ctx, 12, 14)
        r.isClickable = onClick != null
        val col = column(ctx)
        col.addView(label(ctx, title, 15f, ThemeColors.text(ctx)))
        if (!subtitle.isNullOrBlank()) {
            col.addView(label(ctx, subtitle, 12f, ThemeColors.text2(ctx)))
        }
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) r.addView(trailing)
        else if (onClick != null) r.addView(icon(ctx, R.drawable.ic_chevron_right, 18, ThemeColors.muted(ctx)))
        if (onClick != null) r.onClick { onClick() }
        return r
    }

    // ------------------------------------------------------------ navigation

    class Tab(val id: Int, val labelRes: Int, val iconRes: Int)

    val TABS = arrayOf(
        Tab(0, R.string.tab_connect, R.drawable.ic_nav_connect),
        Tab(1, R.string.tab_files, R.drawable.ic_nav_files),
        Tab(2, R.string.tab_history, R.drawable.ic_nav_history),
        Tab(3, R.string.tab_settings, R.drawable.ic_nav_settings)
    )

    /**
     * The four-tab bottom navigation. It is painted under every screen, including the
     * full-screen Transfer/Broadcast screen pushed over Connect or Files.
     */
    fun bottomNav(ctx: Context, active: Int, onSelect: (Int) -> Unit): LinearLayout {
        val bar = row(ctx)
        bar.background = withTopLine(ctx, ThemeColors.surface(ctx))
        bar.pad(6, 6, 6, 6)
        for (tab in TABS) {
            val cell = column(ctx)
            cell.setGravity(Gravity.CENTER)
            cell.pad(0, 8, 0, 8)
            val selected = tab.id == active
            val tint = if (selected) ThemeColors.accentStart(ctx) else ThemeColors.muted(ctx)
            val iv = icon(ctx, tab.iconRes, 22, tint)
            val lp = LinearLayout.LayoutParams(D.dp(ctx, 22f), D.dp(ctx, 22f))
            iv.layoutParams = lp
            val tv = label(ctx, ctx.getString(tab.labelRes), 10.5f, tint, bold = selected, gravity = Gravity.CENTER)
            cell.addView(iv)
            val g = LinearLayout.LayoutParams(D.dp(ctx, 4f), D.dp(ctx, 3f))
            cell.addView(View(ctx), g)
            cell.addView(tv)
            if (selected) {
                val d = GradientDrawable()
                d.setShape(GradientDrawable.RECTANGLE)
                d.setCornerRadius(D.dp(ctx, 14f).toFloat())
                d.setColor(ThemeColors.accentSoft(ctx))
                cell.background = d
            }
            cell.isClickable = true
            cell.onClick { onSelect(tab.id) }
            bar.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    fun withTopLine(ctx: Context, color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setColor(color)
        return d
    }

    /** Screen toolbar: back arrow, title, optional trailing views. */
    fun toolbar(
        ctx: Context,
        title: String,
        subtitle: String? = null,
        onBack: (() -> Unit)? = null,
        trailing: List<View> = emptyList(),
        onTrailingTap: ((Int) -> Unit)? = null
    ): LinearLayout {
        val bar = row(ctx, 12, 12)
        if (onBack != null) {
            val b = icon(ctx, R.drawable.ic_back, 24, ThemeColors.text(ctx))
            b.isClickable = true
            b.onClick { onBack() }
            bar.addView(b)
            bar.addView(hgap(ctx, 10))
        }
        val col = column(ctx)
        col.addView(label(ctx, title, 19f, ThemeColors.text(ctx), bold = true))
        if (!subtitle.isNullOrBlank()) col.addView(label(ctx, subtitle, 11.5f, ThemeColors.text2(ctx), mono = true))
        bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing.forEachIndexed { index, v ->
            bar.addView(v)
            if (onTrailingTap != null) {
                v.isClickable = true
                v.onClick { onTrailingTap(index) }
            }
            bar.addView(hgap(ctx, 6))
        }
        return bar
    }

    // ------------------------------------------------------------ composite rows

    /** Per-item progress bar (Sunflower gradient) with optional live label. */
    fun progressBar(ctx: Context, heightDp: Int = 6): ProgressView = ProgressView(ctx, heightDp)

    fun emptyState(ctx: Context, iconRes: Int, message: String, hint: String? = null): LinearLayout {
        val c = column(ctx)
        c.setGravity(Gravity.CENTER)
        c.pad(24, 48, 24, 48)
        val iv = icon(ctx, iconRes, 46, ThemeColors.accentStart(ctx))
        iv.layoutParams = LinearLayout.LayoutParams(D.dp(ctx, 46f), D.dp(ctx, 46f))
        c.addView(iv)
        c.addView(gap(ctx, 12))
        c.addView(label(ctx, message, 15f, ThemeColors.text(ctx), bold = true, gravity = Gravity.CENTER))
        if (hint != null) {
            c.addView(gap(ctx, 6))
            c.addView(label(ctx, hint, 12.5f, ThemeColors.text2(ctx), gravity = Gravity.CENTER))
        }
        return c
    }
}

/**
 * Progress bar drawn with a plain `android.graphics.LinearGradient` so the full
 * yellow -> amber -> burnt orange -> lime status gradient survives (XML gradients stop at
 * three stops). Never re-tinted by the accent choice - it is a status gradient.
 */
class ProgressView(ctx: Context, private val heightDp: Int = 6) : View(ctx) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var progress = 0f
    private var trackColor = ThemeColors.line2(ctx)
    private val radiusPx = D.dp(ctx, (heightDp / 2f)).toFloat()

    init {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, D.dp(ctx, heightDp.toFloat())
        )
        track.color = trackColor
    }

    fun setProgress(value: Float, animated: Boolean = false) {
        progress = value.coerceIn(0f, 1f)
        if (animated) animate().cancel()
        invalidate()
    }

    fun setTrack(color: Int) {
        trackColor = color
        track.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height
        rect.set(0f, 0f, width.toFloat(), h.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, track)
        if (progress <= 0f) return
        val w = width * progress
        rect.set(0f, 0f, w.coerceAtLeast(radiusPx * 2), height.toFloat())
        fill.shader = ThemeColors.progressShader(width.toFloat())
        canvas.save()
        canvas.clipRect(0f, 0f, w.coerceAtLeast(radiusPx * 2), h.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fill)
        canvas.restore()
    }
}
