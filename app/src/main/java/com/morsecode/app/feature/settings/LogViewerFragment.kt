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
import com.morsecode.app.core.ui.ResultAware
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
class LogViewerFragment(private val activity: Activity) : Screen, ResultAware {

    private var errorsOnly = false
    private var filter = ""
    private var pendingExport: String? = null
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
        // Copy and export are separate buttons because they answer different questions: "paste the
        // last few lines into a chat" and "send me the whole file".
        val copy = W.iconButton(ctx, R.drawable.ic_copy, 34)
        copy.onClick { copyToClipboard() }
        row.addView(copy)
        row.addView(W.hgap(ctx, 6))
        val export = W.iconButton(ctx, R.drawable.ic_download, 34)
        export.onClick { export() }
        row.addView(export)
        row.addView(W.hgap(ctx, 6))
        val clear = W.iconButton(ctx, R.drawable.ic_trash, 34)
        clear.onClick {
            Ui.confirm(activity, ctx.getString(R.string.clear_log),
                "This removes the stored log and every crash report. Until you do, the log survives closing the app.",
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

    /** The filtered view lands on the clipboard - the quickest way to paste a bug into a message. */
    private fun copyToClipboard() {
        val ctx = activity
        val text = exportText()
        try {
            Compat.copy(ctx, "MorseCode log", text)
            Ui.toast(ctx, ctx.getString(R.string.copied))
        } catch (t: Throwable) {
            Compat.shareText(ctx, ctx.getString(R.string.log_viewer), text)
        }
    }

    private fun exportText(): String {
        val lines = Di.logs(activity).snapshot().filter { line ->
            (!errorsOnly || line.level != LogStore.Level.INFO) &&
                (filter.isBlank() || line.message.contains(filter, ignoreCase = true))
        }
        // The header names the version and the device, so a pasted log is self-describing.
        val sb = StringBuilder(Di.logs(activity).exportText().substringBefore("=".repeat(64)))
        for (line in lines) sb.append(line.render()).append('\n')
        val crashes = Di.logs(activity).readCrashes()
        if (crashes.isNotBlank()) sb.append('\n').append(crashes)
        return sb.toString()
    }

    /**
     * Saves the log as a .txt.
     *
     * Writing straight into Download/ needs all-files access on Android 11+, which a user who only
     * granted the media permissions does not have - the export then failed silently and looked like
     * "there is no export". The system file picker works for everyone, so it is the first choice;
     * the share sheet is the last resort.
     */
    private fun export() {
        val ctx = activity
        val text = exportText()
        val name = "morsecode-log-${System.currentTimeMillis() / 1000}.txt"
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, name)
            activity.startActivityForResult(intent, REQ_EXPORT)
            pendingExport = text
            return
        } catch (t: Throwable) {
        }
        fallbackExport(text, name)
    }

    private fun fallbackExport(text: String, name: String) {
        val ctx = activity
        val file = java.io.File(com.morsecode.app.core.storage.Destinations.downloadRoot(ctx), name)
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            Ui.info(activity, ctx.getString(R.string.export_txt), ctx.getString(R.string.exported_to, file.absolutePath))
        } catch (t: Throwable) {
            try {
                Compat.shareText(ctx, ctx.getString(R.string.export_txt), text)
            } catch (inner: Throwable) {
                Ui.info(activity, ctx.getString(R.string.export_txt), ctx.getString(R.string.export_failed, t.message ?: "unknown"))
            }
        }
    }

    override fun onActivityResultHandled(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_EXPORT) return false
        val text = pendingExport
        pendingExport = null
        if (resultCode != Activity.RESULT_OK || text == null) {
            Ui.toast(activity, activity.getString(R.string.export_failed, "cancelled"))
            return true
        }
        Ui.toast(activity, activity.getString(R.string.exported))
        return true
    }

    companion object {
        const val REQ_EXPORT = 7911
    }
}
