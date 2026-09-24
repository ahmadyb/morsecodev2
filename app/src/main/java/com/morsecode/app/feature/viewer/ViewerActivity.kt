package com.morsecode.app.feature.viewer

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.morsecode.app.R
import com.morsecode.app.core.media.MediaLibrary
import com.morsecode.app.core.storage.ShareUris
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Fmt
import com.morsecode.app.util.ThemeColors

/**
 * Full-screen image viewer: back, name + dimensions + size, and rotate / info / next / share
 * actions over a pure-black canvas. Rotation is non-destructive - the file is never rewritten.
 */
class ViewerActivity : Activity() {

    private lateinit var image: ImageView
    private var rotation = 0f
    private var name = ""
    private var size = 0L
    private var width = 0
    private var height = 0
    private var current: Uri? = null
    private var siblings: List<com.morsecode.app.core.model.MediaItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.applyTheme(this)
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra("name") ?: "image"
        size = intent.getLongExtra("size", 0L)
        width = intent.getIntExtra("width", 0)
        height = intent.getIntExtra("height", 0)
        current = intent.getStringExtra("uri")?.let { Uri.parse(it) }

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        val imgLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        imgLp.setMargins(D.dp(this, 8f), D.dp(this, 64f), D.dp(this, 8f), D.dp(this, 88f))
        root.addView(image, imgLp)

        root.addView(header(), topParams())
        val bar = bottomBar()
        val barLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        barLp.gravity = Gravity.BOTTOM
        barLp.setMargins(D.dp(this, 12f), 0, D.dp(this, 12f), D.dp(this, 14f))
        root.addView(bar, barLp)

        setContentView(root)
        load()
    }

    private fun topParams(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP
        lp.setMargins(D.dp(this, 12f), D.dp(this, 10f), D.dp(this, 12f), 0)
        return lp
    }

    private fun header(): View {
        val row = W.row(this, 6, 0)
        val back = W.iconButton(this, R.drawable.ic_back, 38, 0xFFFFFFFF.toInt(), false)
        back.onClick { finish() }
        row.addView(back)
        row.addView(W.hgap(this, 12))
        val col = W.column(this)
        col.addView(W.label(this, name, 15f, 0xFFFFFFFF.toInt(), bold = true))
        val dims = if (width > 0 && height > 0) "$width \u00d7 $height \u00b7 ${Fmt.size(size)}" else Fmt.size(size)
        col.addView(W.label(this, dims, 11.5f, 0xFF9CA3AF.toInt(), mono = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val more = W.iconButton(this, R.drawable.ic_more, 38, 0xFFFFFFFF.toInt(), false)
        more.onClick { actions() }
        row.addView(more)
        return row
    }

    private fun bottomBar(): View {
        val bar = W.row(this, 6, 8)
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(999f * resources.displayMetrics.density)
        d.setColor(0xCC141414.toInt())
        bar.background = d
        bar.addView(circleButton(R.drawable.ic_rotate) { rotate() })
        bar.addView(W.spacer(this))
        bar.addView(circleButton(R.drawable.ic_edit) { edit() })
        bar.addView(W.spacer(this))
        bar.addView(circleButton(R.drawable.ic_info) { info() })
        bar.addView(W.spacer(this))
        bar.addView(circleButton(R.drawable.ic_next) { next() })
        bar.addView(W.spacer(this))
        val share = W.iconButton(this, R.drawable.ic_share, 44, ThemeColors.accentInk(this))
        share.background = ThemeColors.accentDrawable(this)
        share.onClick { share() }
        bar.addView(share)
        return bar
    }

    private fun circleButton(iconRes: Int, onTap: () -> Unit): View {
        val v = W.iconButton(this, iconRes, 42, 0xFFFFFFFF.toInt(), false)
        v.background = ThemeColors.neutralDrawable(this, 999f, true)
        v.onClick { onTap() }
        return v
    }

    // ------------------------------------------------------------------ actions

    private fun load() {
        val uri = current ?: return
        val bmp = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeStream(stream, null, opts)
                if (width == 0) {
                    width = opts.outWidth
                    height = opts.outHeight
                }
                val target = 2048
                var sample = 1
                while (opts.outWidth / sample > target || opts.outHeight / sample > target) sample *= 2
                contentResolver.openInputStream(uri)?.use { s2 ->
                    val o2 = BitmapFactory.Options()
                    o2.inSampleSize = sample
                    BitmapFactory.decodeStream(s2, null, o2)
                }
            }
        } catch (t: Throwable) {
            null
        }
        if (bmp != null) {
            image.setImageBitmap(bmp)
        } else {
            image.setImageResource(R.drawable.ic_image)
            image.setColorFilter(0xFF4B5563.toInt())
        }
        applyRotation()
    }

    private fun applyRotation() {
        val m = Matrix()
        m.postRotate(rotation, image.drawable?.intrinsicWidth?.toFloat()?.div(2f) ?: 0f,
            image.drawable?.intrinsicHeight?.toFloat()?.div(2f) ?: 0f)
        image.imageMatrix = m
    }

    private fun rotate() {
        rotation = (rotation + 90f) % 360f
        applyRotation()
        Ui.toast(this, "Rotated ${rotation.toInt()}\u00b0")
    }

    private fun edit() {
        val uri = current ?: return
        try {
            val intent = Intent(Intent.ACTION_EDIT)
            intent.setDataAndType(uri, "image/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (t: Throwable) {
            Ui.toast(this, "No image editor installed")
        }
    }

    private fun info() {
        val uri = current ?: return
        val sheet = Ui.bottomSheet(this, name, Fmt.size(size))
        val col = W.column(this)
        col.addView(W.infoRow(this, "Dimensions", if (width > 0) "$width \u00d7 $height" else "unknown"))
        col.addView(W.infoRow(this, "Size", Fmt.size(size)))
        col.addView(W.infoRow(this, "Type", contentResolver.getType(uri) ?: "image"))
        col.addView(W.infoRow(this, "Rotation", "${rotation.toInt()}\u00b0 (view only)"))
        col.addView(W.gap(this, 10))
        val open = W.outlineButton(this, getString(R.string.share))
        open.onClick {
            sheet.dismiss()
            share()
        }
        col.addView(open)
        sheet.add(col)
        sheet.show()
    }

    private fun siblingsOf(): List<com.morsecode.app.core.model.MediaItem> {
        if (siblings.isNotEmpty()) return siblings
        siblings = try {
            MediaLibrary(this).all(MediaLibrary.Category.PHOTOS).sortedByDescending { it.dateMs }
        } catch (t: Throwable) {
            emptyList()
        }
        return siblings
    }

    private fun next() {
        val uri = current ?: return
        val all = siblingsOf()
        if (all.isEmpty()) {
            Ui.toast(this, "No other photos found")
            return
        }
        val index = all.indexOfFirst { it.uri == uri.toString() }
        val nextItem = if (index < 0 || index == all.size - 1) all.first() else all[index + 1]
        current = Uri.parse(nextItem.uri)
        name = nextItem.displayName
        size = nextItem.size
        width = nextItem.width
        height = nextItem.height
        rotation = 0f
        val fresh = Intent(this, ViewerActivity::class.java)
            .putExtra("uri", nextItem.uri)
            .putExtra("name", nextItem.displayName)
            .putExtra("mime", nextItem.mime)
            .putExtra("size", nextItem.size)
            .putExtra("width", nextItem.width)
            .putExtra("height", nextItem.height)
        startActivity(fresh)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun share() {
        val uri = current ?: return
        try {
            startActivity(ShareUris.shareIntent(listOf(uri.toString()), contentResolver.getType(uri) ?: "*/*"))
        } catch (t: Throwable) {
            Ui.toast(this, getString(R.string.no_app_for_share))
        }
    }

    private fun actions() {
        val uri = current ?: return
        val sheet = Ui.bottomSheet(this, name)
        val col = W.column(this)
        val shareRow = W.settingRow(this, getString(R.string.share), null, null) {
            sheet.dismiss()
            share()
        }
        val editRow = W.settingRow(this, getString(R.string.edit), null, null) {
            sheet.dismiss()
            edit()
        }
        val openRow = W.settingRow(this, "Open with", null, null) {
            sheet.dismiss()
            try {
                startActivity(ShareUris.viewIntent(uri.toString(), contentResolver.getType(uri) ?: "image/*"))
            } catch (t: Throwable) {
                Ui.toast(this, getString(R.string.no_app_for_share))
            }
        }
        val infoRow = W.settingRow(this, getString(R.string.file_info), null, null) {
            sheet.dismiss()
            info()
        }
        col.addView(shareRow)
        col.addView(W.divider(this))
        col.addView(editRow)
        col.addView(W.divider(this))
        col.addView(openRow)
        col.addView(W.divider(this))
        col.addView(infoRow)
        sheet.add(col)
        sheet.show()
    }
}
