package com.morsecode.app.feature.onboarding

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.morsecode.app.R
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Permissions
import com.morsecode.app.util.ThemeColors

/**
 * Four slides, swipe or tap. Slide two grants the three permissions the app actually uses and
 * never blocks: "Not now" keeps every screen reachable.
 */
class OnboardingActivity : Activity() {

    private lateinit var slides: FrameLayout
    private lateinit var dots: LinearLayout
    private var index = 0
    private lateinit var primary: View
    private lateinit var skip: View

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(ThemeColors.bg(this))
        root.pad(22, 24, 22, 22)

        val top = W.row(this)
        top.addView(W.label(this, getString(R.string.app_name), 15f, ThemeColors.accentStart(this), bold = true))
        top.addView(W.spacer(this))
        val skipView = W.label(this, getString(R.string.skip), 13f, ThemeColors.text2(this))
        skipView.pad(8, 4, 4, 4)
        skipView.isClickable = true
        skipView.onClick { finishOnboarding() }
        skip = skipView
        top.addView(skipView)
        root.addView(top)

        slides = FrameLayout(this)
        root.addView(slides, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dots = W.row(this)
        dots.setGravity(Gravity.CENTER)
        root.addView(dots)
        root.addView(W.gap(this, 18))

        val continueBtn = W.accentButton(this, getString(R.string.continue_))
        continueBtn.onClick {
            if (index < 3) {
                index++
                render()
            } else finishOnboarding()
        }
        root.addView(continueBtn)
        primary = continueBtn

        setContentView(root)

        // Swipe handling is done by hand rather than through GestureDetector: the framework
        // listener's parameter annotations differ between SDK releases and a plain
        // OnTouchListener compiles identically against every API level.
        slides.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downX = event.x
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        if (Math.abs(dx) > 90f) {
                            if (dx < 0 && index < 3) index++
                            else if (dx > 0 && index > 0) index--
                            else return true
                            render()
                        }
                    }
                }
                return true
            }
        })
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun finishOnboarding() {
        com.morsecode.app.di.Di.prefs(this).onboarded = true
        if (!isTaskRoot) {
            // Launched from Settings - just close.
            finish()
            return
        }
        startActivity(Intent(this, com.morsecode.app.MainActivity::class.java))
        finish()
    }

    private fun render() {
        slides.removeAllViews()
        val view = when (index) {
            0 -> slide(R.drawable.ic_send_up, getString(R.string.onb1_title), getString(R.string.onb1_sub))
            1 -> permissionSlide()
            2 -> slide(R.drawable.ic_globe, getString(R.string.onb3_title), getString(R.string.onb3_sub))
            else -> slide(R.drawable.ic_check, getString(R.string.onb4_title), getString(R.string.onb4_sub))
        }
        slides.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dots.removeAllViews()
        for (i in 0..3) {
            val dot = View(this)
            val d = GradientDrawable()
            d.setShape(GradientDrawable.OVAL)
            d.setColor(if (i == index) ThemeColors.accentStart(this) else ThemeColors.muted(this))
            dot.background = d
            val lp = LinearLayout.LayoutParams(D.dp(this, if (i == index) 22f else 8f), D.dp(this, 8f))
            lp.setMargins(D.dp(this, 4f), 0, D.dp(this, 4f), 0)
            dots.addView(dot, lp)
        }
        skip.visibility = if (index == 3) View.INVISIBLE else View.VISIBLE
        setPrimaryLabel(if (index == 3) getString(R.string.open_morsecode) else getString(R.string.continue_))
    }

    private fun setPrimaryLabel(text: String) {
        val btn = primary as? ViewGroup ?: return
        for (i in 0 until btn.childCount) {
            val child = btn.getChildAt(i)
            if (child is android.widget.TextView) child.text = text
        }
    }

    private fun slide(iconRes: Int, title: String, sub: String): View {
        val col = W.column(this)
        col.setGravity(Gravity.CENTER)
        val art = ImageView(this)
        art.setImageResource(iconRes)
        art.setColorFilter(ThemeColors.accentInk(this))
        art.setPadding(D.dp(this, 40f), D.dp(this, 40f), D.dp(this, 40f), D.dp(this, 40f))
        art.background = ThemeColors.accentDrawable(this, 32f)
        val artLp = LinearLayout.LayoutParams(D.dp(this, 160f), D.dp(this, 160f))
        artLp.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(art, artLp)
        col.addView(W.gap(this, 30))
        col.addView(W.label(this, title, 23f, ThemeColors.text(this), bold = true, gravity = Gravity.CENTER))
        col.addView(W.gap(this, 12))
        val body = W.label(this, sub, 14f, ThemeColors.text2(this), gravity = Gravity.CENTER)
        body.setLineSpacing(0f, 1.3f)
        col.addView(body)
        return col
    }

    private fun permissionSlide(): View {
        val col = W.column(this)
        col.setGravity(Gravity.CENTER)
        col.addView(W.label(this, getString(R.string.onb2_title), 23f, ThemeColors.text(this), bold = true, gravity = Gravity.CENTER))
        col.addView(W.gap(this, 8))
        val body = W.label(this, getString(R.string.onb2_sub), 13.5f, ThemeColors.text2(this), gravity = Gravity.CENTER)
        body.setLineSpacing(0f, 1.3f)
        col.addView(body)
        col.addView(W.gap(this, 20))

        val nearbyOk = Permissions.granted(this, Permissions.nearby(this))
        val storageOk = Compat.hasAllFilesAccess(this) || Permissions.granted(this, Permissions.storage(this))
        val notifOk = !Compat.isApi33 || Permissions.granted(this, Permissions.notifications())
        col.addView(permRow("Nearby devices", nearbyOk, Permissions.REQ_NEARBY, Permissions.nearby(this)))
        col.addView(W.gap(this, 8))
        col.addView(permRow("Files and media", storageOk, Permissions.REQ_STORAGE, Permissions.storage(this)))
        col.addView(W.gap(this, 8))
        col.addView(permRow("Notifications", notifOk, Permissions.REQ_NOTIFICATIONS, Permissions.notifications()))
        col.addView(W.gap(this, 16))
        val battery = W.outlineButton(this, getString(R.string.battery_optimization))
        battery.onClick { Permissions.requestBattery(this) }
        col.addView(battery)
        return col
    }

    private fun permRow(title: String, granted: Boolean, code: Int, perms: List<String>): View {
        val ctx = this
        val row = W.row(ctx, 12, 14)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(D.dp(ctx, 14f).toFloat())
        d.setColor(ThemeColors.card(ctx))
        row.background = d
        row.addView(W.label(ctx, title, 14f, ThemeColors.text(ctx), bold = true))
        row.addView(W.spacer(ctx))
        if (granted) {
            row.addView(W.pill(ctx, getString(R.string.granted).uppercase(), ThemeColors.green(ctx)))
        } else {
            val btn = W.pill(ctx, getString(R.string.grant).uppercase(), ThemeColors.accentStart(ctx), filled = true)
            btn.isClickable = true
            btn.onClick {
                if (code == Permissions.REQ_STORAGE && android.os.Build.VERSION.SDK_INT >= 30) {
                    Compat.openAllFilesSettings(this@OnboardingActivity)
                } else {
                    Permissions.request(this@OnboardingActivity, perms, code)
                }
            }
            row.addView(btn)
        }
        return row
    }
}
