package com.morsecode.app.feature.settings

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.morsecode.app.R
import com.morsecode.app.core.logging.LogStore
import com.morsecode.app.core.ui.Screen
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Fmt
import com.morsecode.app.di.Di
import com.morsecode.app.util.ThemeColors

/**
 * The log viewer: live tail, level filter, text filter, export and clear.
 * Monospaced because log lines are read column-first.
 */
class LogViewerFragment(private val activity: Activity) : Screen {

    private var errorsOnly = false
    private var filter = ""
    private lateinit var list: LinearLayout
    private lateinit var host: com.morsecode.app.MainActivity

    private val listener: () -> Unit = { refresh() }

    override fun view(ctx: Activity): View {
        val root = W.column(ctx)
        root.setBackgroundColor(ThemeColors.bg(ctx))
        host = ctx as com.morsecode.app.MainActivity
        root.addView(W.toolbar(ctx, ctx.getString(R.string.log_viewer), null, { host.pop() }, emptyList()))
        root.addView(toolbarRow(ctx))
        val scroll = ScrollView(ctx)
        list = W.column(ctx, 0, 14)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onShown() {
        Di.logs(activity).addListener(listener)
        refresh()
    }

    override fun onHidden() {
        Di.logs(activity).removeListener(listener)
    }

    override fun onBackPressed(): Boolean {
        host.pop()
        return true
    }

    private fun toolbarRow(ctx: Activity): View {
        val row = W.row(ctx, 8, 14)
        val chips = W.row(ctx)
        val all = chip(ctx, "All", !errorsOnly) { errorsOnly = false; refresh() }
        val err = chip(ctx, ctx.getString(R.string.errors_only), errorsOnly) { errorsOnly = true; refresh() }
        chips.addView(all)
        chips.addView(W.hgap(ctx, 6))
        chips.addView(err)
        row.addView(chips)
        row.addView(W.spacer(ctx))
        val find = W.iconButton(ctx, R.drawable.ic_search, 34)
        find.onClick { askFilter() }
        row.addView(find)
        row.addView(W.hgap(ctx, 6))
        val export = W.iconButton(ctx, R.drawable.ic_download, 34)
        export.onClick { export() }
        row.addView(export)
        row.addView(W.hgap(ctx, 6))
        val clear = W.iconButton(ctx, R.drawable.ic_trash, 34)
        clear.onClick {
            Ui.confirm(activity, ctx.getString(R.string.clear_log), "Every in-memory line is removed.",
                positive = ctx.getString(R.string.clear),
                onPositive = {
                    Di.logs(activity).clear()
                    refresh()
                },
                negative = ctx.getString(R.string.cancel),
                destructive = true)
        }
        row.addView(clear)
        return row
    }

    private fun chip(ctx: Activity, text: String, active: Boolean, onTap: () -> Unit): View {
        val v = W.pill(ctx, text, ThemeColors.accentStart(ctx), filled = active)
        v.isClickable = true
        v.onClick { onTap() }
        return v
    }

    private fun askFilter() {
        Ui.prompt(activity, activity.getString(R.string.search), "text to match", filter,
            activity.getString(R.string.ok)) { value ->
            filter = value
            refresh()
        }
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        val ctx = activity
        list.removeAllViews()
        val lines = Di.logs(ctx).snapshot().filter { line ->
            (!errorsOnly || line.level != LogStore.Level.INFO) &&
                (filter.isBlank() || line.message.contains(filter, ignoreCase = true))
        }
        if (lines.isEmpty()) {
            list.addView(W.emptyState(ctx, R.drawable.ic_list, "No log lines yet", "Transfers, WebShare sessions and errors land here."))
            return
        }
        val card = W.card(ctx, 0, 16f, 10)
        for ((i, line) in lines.withIndex()) {
            val row = W.row(ctx, 2, 0)
            val color = when (line.level) {
                LogStore.Level.ERROR -> ThemeColors.Red
                LogStore.Level.WARN -> ThemeColors.Ember2
                else -> ThemeColors.text2(ctx)
            }
            val dot = View(ctx)
            val d = GradientDrawable()
            d.setShape(GradientDrawable.OVAL)
            d.setColor(color)
            row.addView(dot, LinearLayout.LayoutParams(D.dp(ctx, 6f), D.dp(ctx, 6f)))
            row.addView(W.hgap(ctx, 8))
            row.addView(W.label(ctx, line.render().substringBefore(" "), 10.5f, ThemeColors.muted(ctx), mono = true))
            row.addView(W.hgap(ctx, 6))
            val msg = W.label(ctx, line.message, 11.5f, color, mono = true)
            msg.maxLines = 6
            row.addView(msg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setGravity(Gravity.CENTER_VERTICAL)
            card.addView(row)
            if (i < lines.size - 1) card.addView(W.gap(ctx, 6))
        }
        list.addView(card)
        list.addView(W.gap(ctx, 20))
    }

    private fun export() {
        val ctx = activity
        val text = Di.logs(ctx).exportText()
        val file = java.io.File(com.morsecode.app.core.storage.Destinations.downloadRoot(ctx), "morsecode-log-${System.currentTimeMillis() / 1000}.txt")
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            Ui.info(activity, ctx.getString(R.string.export_txt), ctx.getString(R.string.exported_to, file.absolutePath))
        } catch (t: Throwable) {
            try {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, text)
                activity.startActivity(Intent.createChooser(intent, ctx.getString(R.string.export_txt)))
            } catch (inner: Throwable) {
                Ui.info(activity, ctx.getString(R.string.export_txt), ctx.getString(R.string.export_failed, t.message ?: "unknown"))
            }
        }
    }
}
