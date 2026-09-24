package com.morsecode.app.feature.history

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import com.morsecode.app.R
import com.morsecode.app.core.model.GroupHistoryEntry
import com.morsecode.app.core.model.HistoryEntry
import com.morsecode.app.core.model.ItemState
import com.morsecode.app.core.model.TransportKind
import com.morsecode.app.core.storage.ShareUris
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
 * History: Received / Sent segmented control over day-grouped rows.
 *
 * A Broadcast is exactly one row ("Broadcast - 3 phones") with a per-peer breakdown in the
 * details sheet, never one row per peer.
 */
class HistoryFragment(private val activity: Activity) : Screen {

    private var sent = false
    private var query = ""
    private lateinit var body: LinearLayout

    override fun view(ctx: Activity): View {
        val root = W.column(ctx)
        root.setBackgroundColor(ThemeColors.bg(ctx))
        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        body = W.column(ctx, 0, 14)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onShown() = rebuild()

    override fun onBackPressed(): Boolean {
        if (query.isNotEmpty()) {
            query = ""
            rebuild()
            return true
        }
        return false
    }

    private fun rebuild() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        body.addView(header())
        body.addView(W.gap(activity, 10))
        body.addView(W.segmented(activity,
            listOf(activity.getString(R.string.received), activity.getString(R.string.sent)),
            if (sent) 1 else 0) { i ->
            sent = i == 1
            rebuild()
        })
        body.addView(W.gap(activity, 12))

        val direction = if (sent) "sent" else "received"
        val entries = Di.history(activity).entries(direction)
            .filter { query.isBlank() || it.fileName.lowercase().contains(query.lowercase()) || it.peerName.lowercase().contains(query.lowercase()) }
            .sortedByDescending { it.whenMs }
        val groups = Di.history(activity).groups(direction)
            .filter { query.isBlank() || it.label.lowercase().contains(query.lowercase()) }
            .sortedByDescending { it.whenMs }

        if (entries.isEmpty() && groups.isEmpty()) {
            body.addView(W.emptyState(activity, R.drawable.ic_nav_history,
                activity.getString(R.string.empty_history), activity.getString(R.string.empty_state_files_hint)))
            return
        }

        val byDay = LinkedHashMap<String, MutableList<Any>>()
        for (g in groups) byDay.getOrPut(Fmt.dayLabel(g.whenMs)) { ArrayList() }.add(g)
        for (e in entries) byDay.getOrPut(Fmt.dayLabel(e.whenMs)) { ArrayList() }.add(e)

        for ((day, list) in byDay) {
            body.addView(W.label(activity, day.uppercase(), 11f, ThemeColors.muted(activity), bold = true, mono = true))
            body.addView(W.gap(activity, 6))
            val card = W.card(activity, 0, 18f, 4)
            var first = true
            for (row in list) {
                if (!first) card.addView(W.divider(activity))
                if (row is GroupHistoryEntry) card.addView(groupRow(row)) else card.addView(entryRow(row as HistoryEntry))
                first = false
            }
            body.addView(card)
            body.addView(W.gap(activity, 14))
        }
        body.addView(W.gap(activity, 20))
    }

    private fun header(): View {
        val ctx = activity
        val row = W.row(ctx)
        row.addView(W.label(ctx, ctx.getString(R.string.history), 22f, ThemeColors.text(ctx), bold = true))
        row.addView(W.spacer(ctx))
        val search = W.iconButton(ctx, R.drawable.ic_search, 34)
        search.onClick {
            Ui.prompt(activity, ctx.getString(R.string.search), ctx.getString(R.string.search),
                query, ctx.getString(R.string.ok)) { value ->
                query = value
                rebuild()
            }
        }
        row.addView(search)
        row.addView(W.hgap(ctx, 4))
        val sort = W.iconButton(ctx, R.drawable.ic_list, 34)
        sort.onClick {
            val opts = arrayListOf(if (sent) "Sent first" else "Received first", "Newest first", "Largest first")
            Ui.pick(activity, ctx.getString(R.string.sort_by), opts, 0) { i ->
                if (i == 0) {
                    sent = !sent
                    rebuild()
                }
            }
        }
        row.addView(sort)
        row.addView(W.hgap(ctx, 4))
        val trash = W.iconButton(ctx, R.drawable.ic_trash, 34)
        trash.onClick {
            Ui.confirm(activity, ctx.getString(R.string.clear_history_title),
                ctx.getString(R.string.clear_history_body),
                positive = ctx.getString(R.string.clear),
                onPositive = {
                    Di.history(activity).clear()
                    rebuild()
                },
                negative = ctx.getString(R.string.cancel),
                destructive = true)
        }
        row.addView(trash)
        return row
    }

    private fun statusDot(ok: Boolean): View {
        val ctx = activity
        val v = W.label(ctx, if (ok) "\u2713" else "\u2715", 15f,
            if (ok) ThemeColors.green(ctx) else ThemeColors.Red, bold = true)
        v.pad(4, 2, 4, 2)
        return v
    }

    private fun typeColor(entry: HistoryEntry): Int = when {
        entry.fileName.lowercase().endsWith(".apk") -> ThemeColors.Purple
        entry.fileName.lowercase().endsWith(".zip") -> ThemeColors.FileZip
        entry.fileName.lowercase().endsWith(".pdf") -> ThemeColors.FilePdf
        entry.fileName.lowercase().endsWith(".mp3") || entry.fileName.lowercase().endsWith(".flac") -> ThemeColors.FileZip
        entry.fileName.lowercase().endsWith(".mp4") || entry.fileName.lowercase().endsWith(".mov") -> 0xFF2563EB.toInt()
        else -> ThemeColors.FileDoc
    }

    private fun typeIcon(entry: HistoryEntry): Int {
        val n = entry.fileName.lowercase()
        return when {
            n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") -> R.drawable.ic_play
            n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a") -> R.drawable.ic_music
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") -> R.drawable.ic_image
            n.endsWith(".apk") -> R.drawable.ic_apk
            n.endsWith(".zip") -> R.drawable.ic_file
            else -> R.drawable.ic_file
        }
    }

    private fun tileFor(color: Int, iconRes: Int): View {
        val ctx = activity
        val v = W.icon(ctx, iconRes, 40, 0xFFFFFFFF.toInt())
        v.setPadding(D.dp(ctx, 10f), D.dp(ctx, 10f), D.dp(ctx, 10f), D.dp(ctx, 10f))
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(D.dp(ctx, 11f).toFloat())
        d.setColor(color)
        v.background = d
        return v
    }

    private fun entryRow(entry: HistoryEntry): View {
        val ctx = activity
        val row = W.row(ctx, 12, 10)
        row.addView(tileFor(typeColor(entry), typeIcon(entry)))
        row.addView(W.hgap(ctx, 12))
        val col = W.column(ctx)
        col.addView(W.label(ctx, entry.fileName, 14f, ThemeColors.text(ctx), bold = true))
        val peerLine = "${entry.peerName} \u00b7 ${Fmt.size(entry.size)} \u00b7 ${TransportKind.label(entry.transport)}"
        col.addView(W.label(ctx, peerLine, 11f, ThemeColors.text2(ctx), mono = true))
        col.addView(W.label(ctx, Fmt.timeAgo(entry.whenMs), 11f, ThemeColors.muted(ctx), mono = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(statusDot(entry.ok))
        val more = W.iconButton(ctx, R.drawable.ic_more, 30, 0, false)
        more.onClick { entryDetails(entry) }
        row.addView(more)
        row.isClickable = true
        row.onClick { entryDetails(entry) }
        return row
    }

    private fun groupRow(entry: GroupHistoryEntry): View {
        val ctx = activity
        val row = W.row(ctx, 12, 10)
        row.addView(tileFor(ThemeColors.accentStart(ctx), R.drawable.ic_nav_files))
        row.addView(W.hgap(ctx, 12))
        val col = W.column(ctx)
        col.addView(W.label(ctx, ctx.getString(R.string.broadcast_n_phones, entry.phoneCount), 14f,
            ThemeColors.text(ctx), bold = true))
        col.addView(W.label(ctx, "${entry.fileCount} files \u00b7 ${Fmt.size(entry.totalBytes)} \u00b7 " +
            Fmt.timeAgo(entry.whenMs), 11f, ThemeColors.text2(ctx), mono = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(statusDot(entry.allOk))
        val more = W.iconButton(ctx, R.drawable.ic_more, 30, 0, false)
        more.onClick { groupDetails(entry) }
        row.addView(more)
        row.isClickable = true
        row.onClick { groupDetails(entry) }
        return row
    }

    private fun entryDetails(entry: HistoryEntry) {
        val ctx = activity
        val sheet = Ui.bottomSheet(activity, entry.fileName, "${entry.peerName} \u00b7 ${Fmt.size(entry.size)}")
        val col = W.column(ctx)
        col.addView(W.infoRow(ctx, ctx.getString(R.string.details),
            if (entry.ok) ctx.getString(R.string.completed) else ctx.getString(R.string.failed),
            if (entry.ok) ThemeColors.green(ctx) else ThemeColors.Red))
        col.addView(W.infoRow(ctx, ctx.getString(R.string.label_transport), TransportKind.label(entry.transport)))
        col.addView(W.infoRow(ctx, ctx.getString(R.string.details), Fmt.timeAgo(entry.whenMs)))
        if (!entry.error.isNullOrBlank()) col.addView(W.infoRow(ctx, "Error", entry.error, ThemeColors.Red))
        col.addView(W.gap(ctx, 10))
        val buttons = W.row(ctx)
        if (!entry.ok) {
            val retry = W.outlineButton(ctx, ctx.getString(R.string.retry))
            retry.onClick {
                sheet.dismiss()
                Ui.toast(activity, "Re-select this file to retry - History keeps the record")
            }
            buttons.addView(retry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(W.hgap(ctx, 8))
        }
        val open = W.outlineButton(ctx, ctx.getString(R.string.share))
        open.onClick {
            sheet.dismiss()
            if (entry.uri.isNotBlank()) {
                try {
                    activity.startActivity(ShareUris.viewIntent(entry.uri, "*/*"))
                } catch (t: Throwable) {
                    Ui.toast(activity, ctx.getString(R.string.no_app_for_share))
                }
            } else Ui.toast(activity, "The file is no longer indexed")
        }
        buttons.addView(open, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(buttons)
        sheet.add(col)
        sheet.show()
    }

    private fun groupDetails(entry: GroupHistoryEntry) {
        val ctx = activity
        val sheet = Ui.bottomSheet(activity, ctx.getString(R.string.broadcast_n_phones, entry.phoneCount),
            Fmt.timeAgo(entry.whenMs))
        val col = W.column(ctx)
        col.addView(W.label(ctx, ctx.getString(R.string.delivered_to).uppercase(), 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        col.addView(W.gap(ctx, 8))
        for (p in entry.peerOutcomes) {
            val row = W.row(ctx, 6, 0)
            row.addView(W.avatar(ctx, p.peerName.take(1), ThemeColors.avatarAccentFor(p.peerId), 34))
            row.addView(W.hgap(ctx, 10))
            val c = W.column(ctx)
            c.addView(W.label(ctx, p.peerName, 13.5f, ThemeColors.text(ctx), bold = true))
            c.addView(W.label(ctx, "${p.sent} sent \u00b7 ${p.failed} failed \u00b7 ${p.skipped} skipped",
                11f, ThemeColors.text2(ctx), mono = true))
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(statusDot(p.ok))
            col.addView(row)
            col.addView(W.gap(ctx, 8))
        }
        val retryable = entry.peerOutcomes.any { it.failed > 0 || it.skipped > 0 }
        if (retryable) {
            val retry = W.accentButton(ctx, ctx.getString(R.string.retry_failed_only))
            retry.onClick {
                sheet.dismiss()
                Di.engine(activity).retryGroup(entry)
                Ui.toast(activity, "Re-queued ${entry.fileCount} file(s) for the affected phones")
            }
            col.addView(retry)
        }
        col.addView(W.gap(ctx, 6))
        col.addView(W.label(ctx,
            "Outcome: ${if (entry.allOk) ctx.getString(R.string.completed) else ctx.getString(R.string.failed)}",
            12f, if (entry.allOk) ThemeColors.green(ctx) else ThemeColors.Red))
        sheet.add(col)
        sheet.show()
    }

    private fun pendingCount(): Int = Di.engine(activity).items.value.count {
        it.state == ItemState.QUEUED || it.state == ItemState.IN_PROGRESS
    }
}
