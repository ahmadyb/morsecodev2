package com.morsecode.app.feature.connect

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import com.morsecode.app.MainActivity
import com.morsecode.app.R
import com.morsecode.app.core.model.DiscoveredPeer
import com.morsecode.app.core.model.TransportKind
import com.morsecode.app.core.transfer.EngineEvent
import com.morsecode.app.core.transfer.SessionPhase
import com.morsecode.app.core.transfer.TransferEngine
import com.morsecode.app.core.ui.RadarView
import com.morsecode.app.core.ui.Screen
import com.morsecode.app.core.ui.detach
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.Fmt
import com.morsecode.app.di.Di
import com.morsecode.app.feature.settings.ConnectionDoctor
import com.morsecode.app.feature.transfer.TransferFragment
import com.morsecode.app.util.ThemeColors

/**
 * Connect: the dashboard. Own avatar + device name, live radar, Send / Receive, the
 * WebShare · PC card and the recent-devices list (with Broadcast checkboxes on Wi-Fi LAN).
 */
class ConnectFragment(
    private val activity: Activity,
    private val host: MainActivity
) : Screen {

    private val engine: TransferEngine = Di.engine(activity)
    private lateinit var body: LinearLayout
    private lateinit var radar: RadarView
    private val selected = LinkedHashMap<String, DiscoveredPeer>()

    private val peersObserver: (List<DiscoveredPeer>) -> Unit = { rebuild() }
    private val phaseObserver: (SessionPhase) -> Unit = { rebuild() }
    private val transportObserver: (String) -> Unit = { rebuild() }
    private val itemsObserver: (List<com.morsecode.app.core.model.TransferItem>) -> Unit = { rebuild() }
    private val eventObserver: (EngineEvent) -> Unit = { event ->
        if (event is EngineEvent.Message) Ui.toast(activity, event.text)
        rebuild()
    }

    override fun view(ctx: Activity): View {
        val root = W.column(ctx)
        root.setBackgroundColor(ThemeColors.bg(ctx))
        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        body = W.column(ctx, 0, 14)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        radar = RadarView(ctx)
        return root
    }

    override fun onShown() {
        engine.peers.observe(peersObserver)
        engine.phase.observe(phaseObserver)
        engine.transportKind.observe(transportObserver)
        engine.items.observe(itemsObserver)
        engine.events.observe(eventObserver)
        engine.startDiscovery()
        rebuild()
    }

    override fun onHidden() {
        engine.peers.unobserve(peersObserver)
        engine.phase.unobserve(phaseObserver)
        engine.transportKind.unobserve(transportObserver)
        engine.items.unobserve(itemsObserver)
        engine.events.unobserve(eventObserver)
    }

    // ------------------------------------------------------------------ render

    private fun rebuild() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        body.addView(header())
        body.addView(radarBlock())
        body.addView(primaryButtons())
        body.addView(webShareCard())
        body.addView(recentDevices())
        body.addView(W.gap(activity, 20))
    }

    private fun header(): View {
        val ctx = activity
        val row = W.row(ctx, 6, 0)
        row.addView(W.selfAvatar(ctx, Di.prefs(ctx).deviceName.take(1), 46))
        row.addView(W.hgap(ctx, 12))
        val col = W.column(ctx)
        col.addView(W.label(ctx, Di.prefs(ctx).deviceName, 16.5f, ThemeColors.text(ctx), bold = true))
        val status = if (Compat.isWifiConnected(ctx)) "Wi-Fi \u00b7 ${Compat.ssid(ctx)}" else "No Wi-Fi - Nearby available"
        col.addView(W.label(ctx, status, 12f, ThemeColors.text2(ctx), mono = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val help = W.iconButton(ctx, R.drawable.ic_info, 38)
        help.onClick { showHelpMenu() }
        row.addView(help)
        return row
    }

    private fun radarBlock(): View {
        val ctx = activity
        radar.setMode(RadarView.Mode.DISCOVERY)
        radar.setPeers(engine.peers.value)
        val col = W.column(ctx)
        col.addView(radar.detach(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, D.dp(ctx, 190f)))
        val count = engine.peers.value.size
        val caption = when {
            count == 0 -> ctx.getString(R.string.scanning_local_network)
            count == 1 -> ctx.getString(R.string.one_device_nearby)
            else -> ctx.getString(R.string.devices_nearby, count)
        }
        col.addView(W.label(ctx, caption, 15f, ThemeColors.text(ctx), bold = true, gravity = Gravity.CENTER))
        val transports = engine.peers.value.map { it.transport }.distinct()
        val sub = if (transports.isEmpty()) TransportKind.label(engine.transportKind.value)
        else transports.joinToString(" + ") { TransportKind.label(it) }
        col.addView(W.label(ctx, sub, 12f, ThemeColors.text2(ctx), mono = true, gravity = Gravity.CENTER))
        return col
    }

    private fun primaryButtons(): View {
        val ctx = activity
        val row = W.row(ctx, 10, 0)
        val send = W.accentButton(ctx, ctx.getString(R.string.send_arrow), R.drawable.ic_send_up)
        send.onClick {
            Ui.onceTip(activity, "pairing", ctx.getString(R.string.tip_once_pairing))
            host.openTransfer(TransferFragment.Mode.SEND_PICKED, ctx.getString(R.string.send_files))
        }
        val receive = W.outlineButton(ctx, ctx.getString(R.string.receive_arrow), R.drawable.ic_receive_down)
        receive.onClick { host.openTransfer(TransferFragment.Mode.LISTEN, ctx.getString(R.string.receiving)) }
        val lp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp1.setMargins(0, 0, D.dp(ctx, 6f), 0)
        val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp2.setMargins(D.dp(ctx, 6f), 0, 0, 0)
        row.addView(send, lp1)
        row.addView(receive, lp2)
        return row
    }

    private fun webShareCard(): View {
        val ctx = activity
        val web = Di.web(ctx)
        val card = W.card(ctx)
        val top = W.row(ctx)
        top.addView(W.icon(ctx, R.drawable.ic_pc, 22, ThemeColors.accentStart(ctx)))
        top.addView(W.hgap(ctx, 10))
        val col = W.column(ctx)
        col.addView(W.label(ctx, ctx.getString(R.string.webshare_card_title), 15f, ThemeColors.text(ctx), bold = true))
        col.addView(W.label(ctx, web.statusLine(), 11.5f, ThemeColors.text2(ctx), mono = true))
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = android.widget.Switch(ctx)
        toggle.isChecked = web.isRunning()
        toggle.setOnCheckedChangeListener(object : android.widget.CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: android.widget.CompoundButton?, isChecked: Boolean) {
                if (isChecked) {
                    if (!Compat.isWifiConnected(ctx) && !web.hotspot.isOn) {
                        Ui.info(activity, ctx.getString(R.string.webshare),
                            ctx.getString(R.string.webshare_requires_wifi))
                        toggle.isChecked = false
                        return
                    }
                    if (web.start()) {
                        Ui.toast(activity, ctx.getString(R.string.webshare_running, web.currentUrl()))
                    }
                } else {
                    web.stop()
                    Ui.toast(activity, "WebShare stopped")
                }
                rebuild()
            }
        })
        top.addView(toggle)
        card.addView(top)

        if (web.isRunning()) {
            card.addView(W.gap(ctx, 10))
            card.addView(W.divider(ctx))
            val urlRow = W.row(ctx, 8, 0)
            urlRow.addView(W.label(ctx, web.currentUrl(), 14f, ThemeColors.accentStart(ctx), bold = true, mono = true))
            urlRow.addView(W.spacer(ctx))
            val copy = W.pill(ctx, ctx.getString(R.string.copy_address), ThemeColors.accentStart(ctx))
            copy.isClickable = true
            copy.onClick {
                Compat.copy(ctx, "WebShare", web.currentUrl())
                Ui.toast(activity, ctx.getString(R.string.copied))
            }
            urlRow.addView(copy)
            card.addView(urlRow)
            card.addView(W.label(ctx, ctx.getString(R.string.webshare_url_label), 11.5f, ThemeColors.text2(ctx), mono = true))
            card.addView(W.gap(ctx, 8))
            val row = W.row(ctx)
            val stop = W.outlineButton(ctx, ctx.getString(R.string.webshare_stop), destructive = true)
            stop.onClick {
                web.stop()
                Ui.toast(activity, "WebShare stopped")
                rebuild()
            }
            val hotspot = W.outlineButton(ctx, ctx.getString(R.string.webshare_hotspot))
            hotspot.onClick {
                val result = web.startHotspot()
                Ui.info(activity, ctx.getString(R.string.webshare_hotspot),
                    if (result.ok) "SSID ${result.ssid}\nPassword ${result.password}"
                    else "${result.message}\n\n${web.hotspot.instructions()}")
                rebuild()
            }
            row.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(W.hgap(ctx, 8))
            row.addView(hotspot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            card.addView(W.label(ctx, "WebShare keeps running until you stop it - screen off included.",
                11f, ThemeColors.muted(ctx)))
        }
        return card
    }

    private fun recentDevices(): View {
        val ctx = activity
        val col = W.column(ctx)
        val head = W.row(ctx, 8, 0)
        head.addView(W.label(ctx, ctx.getString(R.string.recent_devices).uppercase(), 11f,
            ThemeColors.muted(ctx), bold = true, mono = true))
        head.addView(W.spacer(ctx))
        if (selected.size >= 2) {
            val sendTo = W.pill(ctx, ctx.getString(R.string.send_to_n_devices, selected.size),
                ThemeColors.accentStart(ctx), filled = true)
            sendTo.isClickable = true
            sendTo.onClick {
                val peers = selected.values.toList()
                engine.queueBroadcast(
                    engine.items.value.filter { !it.isTerminal }.mapNotNull { runCatching { android.net.Uri.parse(it.uri) }.getOrNull() },
                    peers
                )
                host.openTransfer(TransferFragment.Mode.BROADCAST, ctx.getString(R.string.broadcasting))
            }
            head.addView(sendTo)
            head.addView(W.hgap(ctx, 6))
        }
        if (engine.peers.value.isNotEmpty()) {
            val clear = W.label(ctx, ctx.getString(R.string.clear), 12f, ThemeColors.accentStart(ctx), bold = true)
            clear.pad(6, 4, 6, 4)
            clear.isClickable = true
            clear.onClick {
                for (p in engine.peers.value) engine.forgetPeer(p.deviceId)
                Di.prefs(ctx).clearRecentDevices()
                selected.clear()
                rebuild()
            }
            head.addView(clear)
        }
        col.addView(head)
        col.addView(W.gap(ctx, 4))

        val live = engine.peers.value
        if (live.isEmpty()) {
            col.addView(W.emptyState(ctx, R.drawable.ic_nav_connect, ctx.getString(R.string.empty_peers),
                ctx.getString(R.string.pairing_hint)))
        } else {
            for (p in live) col.addView(deviceRow(p), withMargins(ctx, 0, 0, 0, 6))
        }

        val recent = Di.prefs(ctx).recentDevices()
            .mapNotNull { entry ->
                val parts = entry.split('\u0001')
                if (parts.size < 4) null else DiscoveredPeer(
                    parts[0], parts[1], parts[2], "", com.morsecode.app.BuildConfig.TCP_PORT,
                    lastSeen = parts[3].toLongOrNull() ?: 0L
                )
            }
            .filter { r -> live.none { it.deviceId == r.deviceId } }
        if (recent.isNotEmpty()) {
            col.addView(W.gap(ctx, 6))
            col.addView(W.label(ctx, "RECENT", 11f, ThemeColors.muted(ctx), bold = true, mono = true))
            for (r in recent.take(5)) col.addView(deviceRow(r), withMargins(ctx, 0, 0, 0, 6))
        }
        return col
    }

    private fun withMargins(ctx: Activity, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(D.dp(ctx, l.toFloat()), D.dp(ctx, t.toFloat()), D.dp(ctx, r.toFloat()), D.dp(ctx, b.toFloat()))
        return lp
    }

    private fun deviceRow(peer: DiscoveredPeer): View {
        val ctx = activity
        val card = W.card(ctx, 1, 16f, 12)
        val row = W.row(ctx)
        row.addView(W.avatar(ctx, peer.initial, ThemeColors.avatarAccentFor(peer.deviceId), 40))
        row.addView(W.hgap(ctx, 10))
        val col = W.column(ctx)
        col.addView(W.label(ctx, peer.name, 14.5f, ThemeColors.text(ctx), bold = true))
        val detail = when {
            peer.transport == TransportKind.NEARBY -> "Bluetooth \u00b7 Nearby"
            peer.address.isNotBlank() -> "${peer.address} \u00b7 LAN"
            else -> TransportKind.label(peer.transport)
        }
        col.addView(W.label(ctx, detail, 11.5f, ThemeColors.text2(ctx), mono = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (engine.isLanActive()) {
            val cb = CheckBox(ctx)
            cb.isChecked = selected.containsKey(peer.deviceId)
            cb.isClickable = false
            cb.setOnCheckedChangeListener(null)
            row.addView(cb)
            card.isClickable = true
            card.onClick { toggleSelection(peer) }
            if (cb.isChecked) card.background = ThemeColors.tagDrawable(ctx, ThemeColors.accentStart(ctx), 16f)
        }

        val pill = W.pill(ctx, TransportKind.label(peer.transport).uppercase(), ThemeColors.accentStart(ctx))
        row.addView(pill)
        row.addView(W.hgap(ctx, 6))
        val connect = W.pill(ctx, ctx.getString(R.string.connect), ThemeColors.accentStart(ctx), filled = true)
        connect.isClickable = true
        connect.onClick {
            engine.connectTo(peer)
            host.openTransfer(TransferFragment.Mode.SEND_PICKED, ctx.getString(R.string.send_files))
        }
        row.addView(connect)
        card.addView(row)
        return card
    }

    private fun toggleSelection(peer: DiscoveredPeer) {
        if (selected.containsKey(peer.deviceId)) selected.remove(peer.deviceId)
        else {
            if (selected.size >= 4) {
                Ui.toast(activity, activity.getString(R.string.max_four_devices))
                return
            }
            selected[peer.deviceId] = peer
        }
        engine.selectedForBroadcast.set(selected.keys.toList())
        rebuild()
    }

    private fun showHelpMenu() {
        val ctx = activity
        val options = arrayListOf(
            ctx.getString(R.string.connection_doctor),
            ctx.getString(R.string.help_faq),
            ctx.getString(R.string.transfer_section) + " \u00b7 " + ctx.getString(R.string.switch_transport)
        )
        Ui.pick(activity, ctx.getString(R.string.help), options, 0) { index ->
            when (index) {
                0 -> ConnectionDoctor.show(activity)
                1 -> host.selectTab(3)
                2 -> {
                    engine.switchTransport()
                    Ui.toast(activity, "Transport: ${TransportKind.label(engine.transportKind.value)}")
                }
            }
        }
    }
}
