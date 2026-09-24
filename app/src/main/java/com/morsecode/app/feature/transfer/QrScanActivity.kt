package com.morsecode.app.feature.transfer

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.morsecode.app.R
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Permissions
import com.morsecode.app.di.Di
import com.morsecode.app.util.ThemeColors

/**
 * QR pairing screen.
 *
 * The camera preview, frame guide and "Manual IP" fallback are fully functional. Decoding the QR
 * payload itself is not bundled in this offline build (no third-party decoder is linked), so the
 * screen always offers the manual address path - which the pairing handshake accepts identically.
 */
class QrScanActivity : Activity(), SurfaceHolder.Callback {

    private var camera: Camera? = null
    private lateinit var preview: SurfaceView
    private lateinit var hint: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        preview = SurfaceView(this)
        preview.holder.addCallback(this)
        root.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val overlay = FrameLayout(this)
        val frame = View(this)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(D.dp(this, 20f).toFloat())
        d.setStroke(D.dp(this, 3f), ThemeColors.accentStart(this))
        d.setColor(0x22000000)
        frame.background = d
        val frameLp = FrameLayout.LayoutParams(D.dp(this, 236f), D.dp(this, 236f))
        frameLp.gravity = Gravity.CENTER
        overlay.addView(frame, frameLp)
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = W.row(this, 6, 14)
        val back = W.iconButton(this, R.drawable.ic_back, 40, 0xFFFFFFFF.toInt(), false)
        back.onClick { finish() }
        top.addView(back)
        top.addView(W.hgap(this, 12))
        top.addView(W.label(this, getString(R.string.scan_qr), 19f, 0xFFFFFFFF.toInt(), bold = true))
        val topLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        topLp.gravity = Gravity.TOP
        topLp.setMargins(D.dp(this, 8f), D.dp(this, 8f), D.dp(this, 8f), 0)
        root.addView(top, topLp)

        val bottom = W.column(this)
        bottom.setGravity(Gravity.CENTER_HORIZONTAL)
        bottom.pad(20, 16, 20, 20)
        val d2 = GradientDrawable()
        d2.setShape(GradientDrawable.RECTANGLE)
        d2.setCornerRadius(D.dp(this, 20f).toFloat())
        d2.setColor(0xCC141414.toInt())
        bottom.background = d2
        hint = W.label(this, getString(R.string.qr_scan_unavailable), 12.5f, 0xFF9CA3AF.toInt(), gravity = Gravity.CENTER)
        bottom.addView(hint)
        bottom.addView(W.gap(this, 12))
        val manual = W.accentButton(this, getString(R.string.manual_ip))
        manual.onClick { askManual() }
        bottom.addView(manual)
        bottom.addView(W.gap(this, 8))
        val showMine = W.outlineButton(this, "My address")
        showMine.onClick {
            Ui.info(this, getString(R.string.ip_address),
                "${Di.prefs(this).deviceName}\n${com.morsecode.app.core.util.Net.localIp()}:" +
                    "${com.morsecode.app.BuildConfig.TCP_PORT}\n\n" +
                    "Type this on the other phone's Scan QR screen.")
        }
        bottom.addView(showMine)
        val bottomLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bottomLp.gravity = Gravity.BOTTOM
        bottomLp.setMargins(D.dp(this, 16f), 0, D.dp(this, 16f), D.dp(this, 20f))
        root.addView(bottom, bottomLp)

        setContentView(root)
        if (!Permissions.granted(this, Permissions.camera())) {
            Permissions.request(this, Permissions.camera(), Permissions.REQ_CAMERA)
        }
    }

    private fun askManual() {
        Ui.prompt(this, getString(R.string.manual_ip_title), getString(R.string.manual_ip_hint),
            "192.168.1.42:${com.morsecode.app.BuildConfig.TCP_PORT}", getString(R.string.connect)) { value ->
            val parts = value.split(":")
            if (parts.size < 2) {
                Ui.toast(this, getString(R.string.invalid_address))
                return@prompt
            }
            val port = parts[1].trim().toIntOrNull()
            if (port == null) {
                Ui.toast(this, getString(R.string.invalid_address))
                return@prompt
            }
            Di.engine(this).connectManual(parts[0].trim(), port)
            Ui.toast(this, getString(R.string.connecting))
            finish()
        }
    }

    // ------------------------------------------------------------------ camera

    override fun surfaceCreated(holder: SurfaceHolder?) {
        try {
            camera = Camera.open()
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
        } catch (t: Throwable) {
            camera = null
            hint.text = "Camera unavailable - use Manual IP to pair instead."
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        try {
            camera?.stopPreview()
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
        } catch (t: Throwable) {
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        try {
            camera?.stopPreview()
            camera?.release()
        } catch (t: Throwable) {
        }
        camera = null
    }

    override fun onPause() {
        super.onPause()
        surfaceDestroyed(null)
    }
}
