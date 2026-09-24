package com.morsecode.app.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import com.morsecode.app.core.model.DiscoveredPeer
import com.morsecode.app.core.util.D
import com.morsecode.app.util.ThemeColors

/**
 * RadarView - one custom canvas, two cheap modes.
 *
 *  DISCOVERY : sweeping radar with animated blips for every discovered peer.
 *  TOPOLOGY  : Broadcast fan-out - sender on the left, radar rings in the middle, one receiver
 *              card per peer on the right joined by dashed accent-coloured lines, each card
 *              showing a coloured avatar, the device name and a live per-peer progress bar.
 *
 * Both modes are a handful of draw calls at 12-20 fps, which is what a 2016 GPU can hold
 * comfortably; the sweep angle is time-based, so it does not depend on frame rate.
 */
class RadarView(ctx: Context) : View(ctx) {

    enum class Mode { DISCOVERY, TOPOLOGY }

    private var mode = Mode.DISCOVERY
    private var peers: List<DiscoveredPeer> = emptyList()
    private var progress: Map<String, Float> = emptyMap()
    private var running = false
    private var lastFrame = 0L
    private var sweep = 0f

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = D.dp(ctx, 1.2f).toFloat()
    }
    private val ringFaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = D.dp(ctx, 1f).toFloat()
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = D.dp(ctx, 1.3f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(D.dp(ctx, 6f).toFloat(), D.dp(ctx, 5f).toFloat()), 0f)
    }
    private val barTrack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = D.sp(ctx, 11f)
        typeface = W.BOLD
    }
    private val textSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = D.sp(ctx, 9f)
    }

    init {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, D.dp(ctx, 190f)
        )
    }

    fun setMode(m: Mode) {
        mode = m
        if (m == Mode.DISCOVERY) layoutParams = layoutParams.apply { height = D.dp(context, 190f) }
        else layoutParams = layoutParams.apply { height = D.dp(context, 168f) }
        requestLayout()
        invalidate()
    }

    fun setPeers(list: List<DiscoveredPeer>) {
        peers = list
        invalidate()
    }

    fun setBroadcast(list: List<DiscoveredPeer>, progressByPeer: Map<String, Float>) {
        peers = list
        progress = progressByPeer
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (lastFrame != 0L) {
                val dt = (now - lastFrame) / 1000f
                sweep = (sweep + dt * 130f) % 360f
            }
            lastFrame = now
            invalidate()
            postDelayed(this, 50L)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.DISCOVERY -> drawDiscovery(canvas)
            Mode.TOPOLOGY -> drawTopology(canvas)
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun drawDiscovery(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = (minOf(width, height) / 2f) - D.dp(context, 6f)

        ring.color = ThemeColors.withAlpha(ThemeColors.accentStart(context), 0.22f)
        ringFaint.color = ThemeColors.withAlpha(ThemeColors.accentStart(context), 0.10f)

        canvas.drawCircle(cx, cy, maxR, ring)
        canvas.drawCircle(cx, cy, maxR * 0.66f, ringFaint)
        canvas.drawCircle(cx, cy, maxR * 0.33f, ringFaint)

        // sweeping beam
        val sweepPaintLocal = sweepPaint
        sweepPaintLocal.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                ThemeColors.withAlpha(ThemeColors.accentStart(context), 0f),
                ThemeColors.withAlpha(ThemeColors.accentStart(context), 0.02f),
                ThemeColors.withAlpha(ThemeColors.accentStart(context), 0.42f)
            ),
            floatArrayOf(0f, 0.86f, 1f)
        )
        canvas.save()
        canvas.rotate(sweep, cx, cy)
        canvas.drawCircle(cx, cy, maxR, sweepPaintLocal)
        canvas.restore()

        val beamAngle = Math.toRadians(sweep.toDouble())
        val bx = cx + (maxR * Math.cos(beamAngle)).toFloat()
        val by = cy + (maxR * Math.sin(beamAngle)).toFloat()
        val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ThemeColors.withAlpha(ThemeColors.accentStart(context), 0.55f)
            strokeWidth = D.dp(context, 1.4f).toFloat()
        }
        canvas.drawLine(cx, cy, bx, by, beam)

        // centre: the local device
        centerDot.color = ThemeColors.accentStart(context)
        canvas.drawCircle(cx, cy, D.dp(context, 9f).toFloat(), centerDot)
        centerDot.color = ThemeColors.bg(context)
        canvas.drawCircle(cx, cy, D.dp(context, 3.4f).toFloat(), centerDot)

        // blips
        val now = System.currentTimeMillis()
        peers.take(6).forEachIndexed { index, peer ->
            val angle = Math.toRadians((index * 61.0 + 24.0) + sweep * 0.12)
            val radius = maxR * (0.44f + 0.12f * (index % 3))
            val px = cx + (radius * Math.cos(angle)).toFloat()
            val py = cy + (radius * Math.sin(angle)).toFloat()
            val accent = peerAccent(peer)
            blip.color = ThemeColors.withAlpha(accent, 0.20f)
            val pulse = 1f + 0.25f * kotlin.math.sin((now % 2000) / 2000f * 6.28f)
            canvas.drawCircle(px, py, D.dp(context, 13f).toFloat() * pulse, blip)
            blip.color = accent
            canvas.drawCircle(px, py, D.dp(context, 5.5f).toFloat(), blip)
        }
    }

    // ---------------------------------------------------------------- topology

    private fun drawTopology(canvas: Canvas) {
        val pad = D.dp(context, 10f).toFloat()
        val cx = width * 0.44f
        val cy = height / 2f
        val maxR = minOf(width * 0.20f, height * 0.42f)

        // rings behind the sender
        ring.color = ThemeColors.withAlpha(ThemeColors.accentStart(context), 0.18f)
        ringFaint.color = ThemeColors.withAlpha(ThemeColors.accentStart(context), 0.10f)
        canvas.drawCircle(cx, cy, maxR, ringFaint)
        canvas.drawCircle(cx, cy, maxR * 0.66f, ringFaint)
        canvas.drawCircle(cx, cy, maxR * 0.33f, ringFaint)
        val angle = Math.toRadians((sweep * 0.6).toDouble())
        canvas.drawLine(
            cx, cy,
            cx + (maxR * Math.cos(angle)).toFloat(),
            cy + (maxR * Math.sin(angle)).toFloat(),
            ring
        )

        // sender tile
        val tileW = D.dp(context, 54f).toFloat()
        val tileH = D.dp(context, 86f).toFloat()
        val tileLeft = pad
        val tileTop = cy - tileH / 2f
        val tile = RectF(tileLeft, tileTop, tileLeft + tileW, tileTop + tileH)
        card.color = ThemeColors.card(context)
        canvas.drawRoundRect(tile, D.dp(context, 12f).toFloat(), D.dp(context, 12f).toFloat(), card)
        cardStroke.color = ThemeColors.accentStart(context)
        cardStroke.strokeWidth = D.dp(context, 1.4f).toFloat()
        canvas.drawRoundRect(tile, D.dp(context, 12f).toFloat(), D.dp(context, 12f).toFloat(), cardStroke)

        // little logo mark inside the sender tile
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeColors.accentStart(context) }
        val mx = tile.centerX()
        val my = tile.top + tileH * 0.30f
        val markR = D.dp(context, 14f).toFloat()
        canvas.drawCircle(mx, my, markR, mark)
        mark.color = ThemeColors.accentInk(context)
        val barW = D.dp(context, 1.8f).toFloat()
        canvas.drawRect(mx - markR * 0.55f, my - markR * 0.42f, mx - markR * 0.55f + barW, my + markR * 0.42f, mark)
        canvas.drawRect(mx - markR * 0.18f, my - markR * 0.10f, mx - markR * 0.18f + barW, my + markR * 0.42f, mark)
        canvas.drawRect(mx + markR * 0.20f, my - markR * 0.42f, mx + markR * 0.20f + barW, my + markR * 0.42f, mark)
        canvas.drawRect(mx - markR * 0.55f, my + markR * 0.55f, mx + markR * 0.55f, my + markR * 0.55f + barW * 0.9f, mark)
        textSmall.color = ThemeColors.text2(context)
        textSmall.textAlign = Paint.Align.CENTER
        canvas.drawText("Sender", mx, tile.bottom + D.sp(context, 12f), textSmall)
        textSmall.textAlign = Paint.Align.LEFT

        // receiver cards
        val count = peers.take(4)
        val cardH = D.dp(context, 34f).toFloat()
        val gapY = D.dp(context, 8f).toFloat()
        val totalH = count.size * cardH + (count.size - 1) * gapY
        var y = cy - totalH / 2f
        val cardW = minOf(width * 0.36f, D.dp(context, 150f).toFloat())
        val cardX = width - cardW - pad

        for (peer in count) {
            val accent = peerAccent(peer)
            // dashed link from the sender tile to this card
            dash.color = ThemeColors.withAlpha(ThemeColors.accentStart(context), 0.42f)
            val path = Path()
            path.moveTo(tile.right, cy)
            path.cubicTo(
                cx, cy,
                cardX - D.dp(context, 20f).toFloat(), y + cardH / 2f,
                cardX, y + cardH / 2f
            )
            canvas.drawPath(path, dash)

            val rect = RectF(cardX, y, cardX + cardW, y + cardH)
            card.color = ThemeColors.card(context)
            canvas.drawRoundRect(rect, D.dp(context, 10f).toFloat(), D.dp(context, 10f).toFloat(), card)
            cardStroke.color = ThemeColors.accentLine(context)
            cardStroke.strokeWidth = D.dp(context, 1f).toFloat()
            canvas.drawRoundRect(rect, D.dp(context, 10f).toFloat(), D.dp(context, 10f).toFloat(), cardStroke)

            // avatar
            val avatarR = cardH * 0.32f
            blip.color = accent
            canvas.drawCircle(cardX + avatarR + D.dp(context, 6f), y + cardH / 2f, avatarR, blip)

            text.color = ThemeColors.text(context)
            text.textSize = D.sp(context, 10f)
            canvas.drawText(
                peer.name.take(12),
                cardX + avatarR * 2 + D.dp(context, 10f),
                y + cardH * 0.40f,
                text
            )

            // live per-peer progress bar
            val barLeft = cardX + avatarR * 2 + D.dp(context, 10f)
            val barRight = cardX + cardW - D.dp(context, 8f)
            val barTop = y + cardH * 0.58f
            val barH = D.dp(context, 4f).toFloat()
            barTrack.color = ThemeColors.line2(context)
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barRight, barTop + barH), barH / 2f, barH / 2f, barTrack
            )
            val p = progress[peer.deviceId] ?: 0f
            if (p > 0f) {
                barFill.shader = LinearGradient(
                    barLeft, barTop, barRight, barTop,
                    intArrayOf(ThemeColors.Sun, ThemeColors.Ember, ThemeColors.Lime),
                    null, Shader.TileMode.CLAMP
                )
                val w = (barRight - barLeft) * p.coerceIn(0f, 1f)
                if (w > barH) {
                    canvas.drawRoundRect(
                        RectF(barLeft, barTop, barLeft + w, barTop + barH), barH / 2f, barH / 2f, barFill
                    )
                }
            }
            textSmall.color = ThemeColors.accentStart(context)
            textSmall.textAlign = Paint.Align.RIGHT
            textSmall.textSize = D.sp(context, 9f)
            canvas.drawText(
                if (p > 0f) "${(p * 100).toInt()}%" else "\u2013",
                cardX + cardW - D.dp(context, 8f),
                y + cardH * 0.40f,
                textSmall
            )
            textSmall.textAlign = Paint.Align.LEFT
            y += cardH + gapY
        }
    }

    private fun peerAccent(peer: DiscoveredPeer): Int {
        val accent = ThemeColors.avatarAccentFor(peer.deviceId)
        return if (ThemeColors.isDark(context)) accent.darkStart else accent.lightStart
    }
}
