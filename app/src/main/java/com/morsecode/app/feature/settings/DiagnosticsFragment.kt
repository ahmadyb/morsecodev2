package com.morsecode.app.feature.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.morsecode.app.BuildConfig
import com.morsecode.app.R
import com.morsecode.app.core.model.TransportKind
import com.morsecode.app.core.transfer.TransferService
import com.morsecode.app.core.ui.Screen
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.DeviceTier
import com.morsecode.app.core.util.Fmt
import com.morsecode.app.core.util.Net
import com.morsecode.app.di.Di
import com.morsecode.app.util.ThemeColors

/**
 * Diagnostics: the Connection Doctor checklist plus the technical facts support asks for -
 * addresses, ports, device tier, journal state and the WebShare session count.
 */
class DiagnosticsFragment(private val activity: Activity) : Screen {

    private lateinit var body: LinearLayout
    private lateinit var host: com.morsecode.app.MainActivity

    override fun view(ctx: Activity): View {
        val root = W.column(ctx)
        root.setBackgroundColor(ThemeColors.bg(ctx))
        host = ctx as com.morsecode.app.MainActivity
        root.addView(W.toolbar(ctx, ctx.getString(R.string.diagnostics), null, { host.pop() }, emptyList()))
        val scroll = ScrollView(ctx)
        body = W.column(ctx, 0, 14)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onShown() = rebuild()

    override fun onBackPressed(): Boolean {
        host.pop()
        return true
    }

    private fun rebuild() {
        if (!::body.isInitialized) return
        val ctx = activity
        val engine = Di.engine(ctx)
        val web = Di.web(ctx)
        body.removeAllViews()

        body.addView(W.label(ctx, ctx.getString(R.string.connection_doctor).uppercase(), 11f,
            ThemeColors.muted(ctx), bold = true, mono = true))
        body.addView(W.gap(ctx, 8))
        body.addView(ConnectionDoctor.build(ctx))
        body.addView(W.gap(ctx, 16))

        body.addView(W.label(ctx, "Environment".uppercase(), 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        body.addView(W.gap(ctx, 8))
        val card = W.card(ctx, 0, 16f, 6)
        card.addView(W.infoRow(ctx, "Device name", Di.prefs(ctx).deviceName))
        card.addView(W.infoRow(ctx, "Device ID", engine.deviceId().take(16)))
        card.addView(W.infoRow(ctx, "Local IP", Net.localIp()))
        card.addView(W.infoRow(ctx, "Subnet", Net.subnetOf(Net.localIp())))
        card.addView(W.infoRow(ctx, "Transport", TransportKind.label(engine.transportKind.value)))
        card.addView(W.infoRow(ctx, "UDP discovery", "${BuildConfig.UDP_DISCOVERY_PORT} (beacon 1200 ms)"))
        card.addView(W.infoRow(ctx, "TCP control", "${BuildConfig.TCP_PORT}"))
        card.addView(W.infoRow(ctx, "WebShare HTTP", "${BuildConfig.WEB_PORT} \u00b7 " +
            if (web.isRunning()) "running" else "stopped",
            if (web.isRunning()) ThemeColors.green(ctx) else ThemeColors.muted(ctx)))
        card.addView(W.infoRow(ctx, "WebShare sessions", "${web.sessionCount.value}"))
        card.addView(W.infoRow(ctx, "WebShare URL", web.currentUrl().ifBlank { "\u2014" }))
        card.addView(W.infoRow(ctx, "Device tier",
            (if (DeviceTier.isOldDevice) "low" else "high") +
                " \u00b7 max ${DeviceTier.maxParallelPeers} peers \u00b7 ${DeviceTier.thumbnailPx} px thumbs"))
        card.addView(W.infoRow(ctx, "Foreground service", if (TransferService.isRunning) "running" else "idle"))
        card.addView(W.infoRow(ctx, "Peers online", "${engine.peers.value.size}"))
        card.addView(W.infoRow(ctx, "Queue depth", "${engine.activeItems().size} active of ${engine.items.value.size}"))
        body.addView(card)
        body.addView(W.gap(ctx, 14))

        body.addView(W.label(ctx, "Journal".uppercase(), 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        body.addView(W.gap(ctx, 8))
        val journal = Di.journal(ctx).load()
        val journalCard = W.card(ctx, 0, 16f, 6)
        journalCard.addView(W.infoRow(ctx, "Entries", "${journal.size}"))
        journalCard.addView(W.infoRow(ctx, "Resumable entries", "${journal.count { it.bytesTransferred > 0 }} with bytes on disk"))
        for (item in journal.take(6)) {
            journalCard.addView(W.infoRow(ctx, item.displayName,
                "${item.state} \u00b7 ${Fmt.size(item.bytesTransferred)}/${Fmt.size(item.totalBytes)}"))
        }
        body.addView(journalCard)
        body.addView(W.gap(ctx, 14))

        val buttons = W.row(ctx)
        val copy = W.outlineButton(ctx, "Copy report")
        copy.onClick {
            Compat.copy(ctx, "MorseCode report", report())
            Ui.toast(ctx, ctx.getString(R.string.copied))
        }
        val openLogs = W.outlineButton(ctx, ctx.getString(R.string.log_viewer))
        openLogs.onClick { host.push(LogViewerFragment(ctx)) }
        buttons.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(W.hgap(ctx, 8))
        buttons.addView(openLogs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(buttons)
        body.addView(W.gap(ctx, 20))
    }

    private fun report(): String {
        val ctx = activity
        val engine = Di.engine(ctx)
        val web = Di.web(ctx)
        val sb = StringBuilder()
        sb.append("MorseCode ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("Device: ${Di.prefs(ctx).deviceName} (${engine.deviceId()})\n")
        sb.append("IP ${Net.localIp()} / ${Net.subnetOf(Net.localIp())}\n")
        sb.append("Transport: ${TransportKind.label(engine.transportKind.value)}\n")
        sb.append("Peers: ${engine.peers.value.joinToString { it.name + "(" + it.transport + ")" }}\n")
        sb.append("WebShare: ${if (web.isRunning()) web.currentUrl() else "stopped"} sessions=${web.sessionCount.value}\n")
        sb.append("Tier: ${if (DeviceTier.isOldDevice) "low" else "high"}, maxPeers=${DeviceTier.maxParallelPeers}\n")
        sb.append("Checks:\n")
        for (c in ConnectionDoctor.checks(ctx)) {
            sb.append("  [${if (c.light == 0) "OK" else if (c.light == 1) "WARN" else "FAIL"}] ${c.title} - ${c.detail}\n")
        }
        return sb.toString()
    }
}
