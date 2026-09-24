package com.morsecode.app.util

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import com.morsecode.app.di.Di

/**
 * Sunflower Hue - the single visual system for the whole app.
 *
 * Dark mode is the default; light mode is an exact structural mirror.
 * Five user selectable accents sit on top; status colours never move with the accent.
 */
object ThemeColors {

    // SURFACES - DARK
    const val BgDark = 0xFF0B0B0B.toInt()
    const val SurfaceDark = 0xFF141414.toInt()
    const val CardDark = 0xFF1A1A1A.toInt()
    const val CardDark2 = 0xFF1F1F1F.toInt()
    const val CardDark3 = 0xFF262626.toInt()
    const val TextDark = 0xFFF5F5F5.toInt()
    const val TextDark2 = 0xFFA3A3A3.toInt()
    const val MutedDark = 0xFF6B6B6B.toInt()

    // SURFACES - LIGHT
    const val BgLight = 0xFFEEEDE7.toInt()
    const val SurfaceLight = 0xFFFFFFFF.toInt()
    const val CardLight = 0xFFFFFFFF.toInt()
    const val CardLight2 = 0xFFF5F4EE.toInt()
    const val CardLight3 = 0xFFE8E7E0.toInt()
    const val TextLight = 0xFF0F172A.toInt()
    const val TextLight2 = 0xFF475569.toInt()
    const val MutedLight = 0xFF94A3B8.toInt()

    // Sunflower core
    const val Sun = 0xFFFACC15.toInt()
    const val Sun2 = 0xFFEAB308.toInt()
    const val SunDeep = 0xFFA16207.toInt()
    const val Lime = 0xFF84CC16.toInt()
    const val Lime2 = 0xFF65A30D.toInt()
    const val Ember = 0xFFEA580C.toInt()
    const val Ember2 = 0xFFF97316.toInt()

    // status
    const val Green = 0xFF22C55E.toInt()
    const val Green2 = 0xFF16A34A.toInt()
    const val Red = 0xFFEF4444.toInt()
    const val Purple = 0xFF8B5CF6.toInt()
    const val Blue = 0xFF38BDF8.toInt()

    // file type colours
    const val FilePdf = 0xFFF87171.toInt()
    const val FileDoc = 0xFF60A5FA.toInt()
    const val FileZip = 0xFFFACC15.toInt()
    const val FileImg = 0xFF65A30D.toInt()

    const val Transparent = 0

    /** The five selectable accent swatches, in Settings display order. */
    enum class Accent(
        val id: String,
        val labelRes: Int,
        val darkStart: Int,
        val darkEnd: Int,
        val lightStart: Int,
        val lightEnd: Int
    ) {
        YELLOW("yellow", com.morsecode.app.R.string.color_yellow, Sun, Sun2, Sun2, 0xFFCA8A04.toInt()),
        GREEN("green", com.morsecode.app.R.string.color_green, Lime, Lime2, Lime2, 0xFF4D7C0F.toInt()),
        ORANGE("orange", com.morsecode.app.R.string.color_orange, Ember, Ember2, Ember, 0xFFC2410C.toInt()),
        PURPLE("purple", com.morsecode.app.R.string.color_purple, Purple, 0xFF7C3AED.toInt(), 0xFF7C3AED.toInt(), 0xFF6D28D9.toInt()),
        BLUE("blue", com.morsecode.app.R.string.color_blue, Blue, 0xFF0284C7.toInt(), 0xFF0284C7.toInt(), 0xFF075985.toInt());

        companion object {
            fun from(id: String?): Accent = values().firstOrNull { it.id == id } ?: YELLOW
        }
    }

    /** Peer avatar colours are auto-assigned (never the user's own accent) and stable per device id. */
    fun avatarAccentFor(deviceId: String): Accent {
        val values = Accent.values()
        var h = 0
        for (c in deviceId) h = h * 31 + c.code
        return values[(h and 0x7FFFFFFF) % values.size]
    }

    fun isDark(ctx: Context): Boolean = Di.prefs(ctx).darkMode

    /** Applies the dark/light theme to an Activity before super.onCreate(). */
    fun applyTheme(activity: android.app.Activity) {
        val prefs = Di.prefs(activity)
        activity.setTheme(if (prefs.darkMode) com.morsecode.app.R.style.McTheme_Dark
        else com.morsecode.app.R.style.McTheme_Light)
    }

    // ---- semantic colours, resolved for the current mode -------------------

    fun bg(ctx: Context) = if (isDark(ctx)) BgDark else BgLight
    fun surface(ctx: Context) = if (isDark(ctx)) SurfaceDark else SurfaceLight
    fun card(ctx: Context) = if (isDark(ctx)) CardDark else CardLight
    fun card2(ctx: Context) = if (isDark(ctx)) CardDark2 else CardLight2
    fun card3(ctx: Context) = if (isDark(ctx)) CardDark3 else CardLight3
    fun text(ctx: Context) = if (isDark(ctx)) TextDark else TextLight
    fun text2(ctx: Context) = if (isDark(ctx)) TextDark2 else TextLight2
    fun muted(ctx: Context) = if (isDark(ctx)) MutedDark else MutedLight
    fun line(ctx: Context) = if (isDark(ctx)) 0x12FFFFFF else 0x1A0F172A
    fun line2(ctx: Context) = if (isDark(ctx)) 0x24FFFFFF else 0x2E0F172A
    fun green(ctx: Context) = if (isDark(ctx)) Green else Green2

    fun accent(ctx: Context): Accent = Di.prefs(ctx).accent
    fun accentStart(ctx: Context): Int =
        if (isDark(ctx)) accent(ctx).darkStart else accent(ctx).lightStart
    fun accentEnd(ctx: Context): Int =
        if (isDark(ctx)) accent(ctx).darkEnd else accent(ctx).lightEnd
    fun accentInk(ctx: Context): Int = if (isDark(ctx)) 0xFF2A1702.toInt() else Color.WHITE
    fun accentSoft(ctx: Context): Int = withAlpha(accentStart(ctx), if (isDark(ctx)) 0.16f else 0.18f)
    fun accentLine(ctx: Context): Int = withAlpha(accentStart(ctx), if (isDark(ctx)) 0.40f else 0.50f)

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    // ---- drawables ---------------------------------------------------------

    /** Rounded card surface. */
    fun cardDrawable(ctx: Context, level: Int = 0, radiusDp: Float = 18f): GradientDrawable {
        val c = when (level) {
            1 -> card2(ctx)
            2 -> card3(ctx)
            else -> card(ctx)
        }
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(radiusDp * ctx.resources.displayMetrics.density)
        d.setColor(c)
        if (!isDark(ctx)) d.setStroke((1 * ctx.resources.displayMetrics.density).toInt(), line2(ctx))
        return d
    }

    /** Accent filled, fully rounded button background. */
    fun accentDrawable(ctx: Context, radiusDp: Float = 999f): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(accentStart(ctx), accentEnd(ctx)))
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(radiusDp * ctx.resources.displayMetrics.density)
        return d
    }

    /** Neutral / outline button background. */
    fun neutralDrawable(ctx: Context, radiusDp: Float = 999f, filled: Boolean = false): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(radiusDp * ctx.resources.displayMetrics.density)
        if (filled) d.setColor(card3(ctx)) else d.setColor(Color.TRANSPARENT)
        d.setStroke((1 * ctx.resources.displayMetrics.density).toInt(), line2(ctx))
        return d
    }

    fun tagDrawable(ctx: Context, color: Int, radiusDp: Float = 999f): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(radiusDp * ctx.resources.displayMetrics.density)
        d.setColor(withAlpha(color, if (isDark(ctx)) 0.16f else 0.14f))
        d.setStroke((1 * ctx.resources.displayMetrics.density).toInt(), withAlpha(color, 0.42f))
        return d
    }

    fun successDrawable(ctx: Context): GradientDrawable {
        val g = if (isDark(ctx)) intArrayOf(Green, Green2) else intArrayOf(Green2, 0xFF15803D.toInt())
        val d = GradientDrawable(GradientDrawable.Orientation.TL_BR, g)
        d.setCornerRadius(999f * ctx.resources.displayMetrics.density)
        return d
    }

    fun dangerDrawable(ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(999f * ctx.resources.displayMetrics.density)
        d.setColor(withAlpha(Red, 0.16f))
        d.setStroke((1 * ctx.resources.displayMetrics.density).toInt(), withAlpha(Red, 0.5f))
        return d
    }

    /** Accent gradient as a shader (avatars, hero panels). */
    fun accentShader(ctx: Context, width: Float, height: Float): Shader =
        LinearGradient(0f, 0f, width, height, accentStart(ctx), accentEnd(ctx), Shader.TileMode.CLAMP)

    /** Brand gradient used by the logo tile and progress rails. */
    fun brandShader(width: Float): Shader = LinearGradient(
        0f, 0f, width, 0f,
        intArrayOf(Sun, Ember, Lime), null, Shader.TileMode.CLAMP
    )

    /** Fixed status/progress gradient - never affected by the accent choice. */
    fun progressShader(width: Float): Shader = LinearGradient(
        0f, 0f, width, 0f,
        intArrayOf(Sun, 0xFFF59E0B.toInt(), Ember, Lime),
        floatArrayOf(0f, 0.30f, 0.55f, 1f),
        Shader.TileMode.CLAMP
    )

    fun accentNameRes(ctx: Context): Int = accent(ctx).labelRes
}
