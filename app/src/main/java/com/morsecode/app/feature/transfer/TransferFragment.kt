package com.morsecode.app.feature.transfer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.morsecode.app.MainActivity
import com.morsecode.app.R
import com.morsecode.app.core.model.DiscoveredPeer
import com.morsecode.app.core.model.Direction
import com.morsecode.app.core.model.ItemState
import com.morsecode.app.core.model.TransportKind
import com.morsecode.app.core.model.TransferItem
import com.morsecode.app.core.transfer.EngineEvent
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.transfer.SessionPhase
import com.morsecode.app.core.transfer.SoundFx
import com.morsecode.app.core.transfer.TransferEngine
import com.morsecode.app.core.ui.ProgressView
import com.morsecode.app.core.ui.RadarView
import com.morsecode.app.core.ui.Screen
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.ui.TransportBanner
import com.morsecode.app.core.ui.detach
import com.morsecode.app.core.ui.Ui
import com.morsecode.app.core.ui.W
import com.morsecode.app.core.ui.onClick
import com.morsecode.app.core.ui.pad
import com.morsecode.app.core.util.D
import com.morsecode.app.core.util.DeviceTier
import com.morsecode.app.core.util.Fmt
import com.morsecode.app.core.util.Permissions
import com.morsecode.app.di.Di
import com.morsecode.app.feature.settings.ConnectionDoctor
import com.morsecode.app.util.ThemeColors

/**
 * The Transfer screen: Send / Receive / Broadcast.
 *
 * It is pushed full screen from Connect (Send / Receive) or from Files (after selecting items),
 * and the bottom nav stays visible underneath. Everything it renders comes from the engine's
 * observable state - the screen itself holds no transfer state.
 */
class TransferFragment(
    private val activity: Activity,
    private val mode: Mode,
    private val initialTitle: String? = null
) : Screen {

    enum class Mode { SEARCH_SEND, LISTEN, SEND_PICKED, BROADCAST }

    var pendingUris: List<Uri> = emptyList()
    private val engine: TransferEngine = Di.engine(activity)
    private val selected = LinkedHashMap<String, DiscoveredPeer>()
    private var batchId: String = engine.newBatchId()

    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var actionBar: LinearLayout
    private lateinit var radar: RadarView

    private val itemsObserver: (List<TransferItem>) -> Unit = { rebuild() }
    private val peersObserver: (List<DiscoveredPeer>) -> Unit = { rebuild() }
    private val phaseObserver: (SessionPhase) -> Unit = { rebuild() }
    private val transportObserver: (String) -> Unit = { rebuild() }
    private val eventObserver: (EngineEvent) -> Unit = { event -> onEngineEvent(event) }

    override fun view(ctx: Activity): View {
        root = W.column(ctx)
        root.setBackgroundColor(ThemeColors.bg(ctx))

        val overflow = W.icon(ctx, R.drawable.ic_more, 22, ThemeColors.text2(ctx))
        overflow.isClickable = true
        overflow.onClick { showOverflow() }

        val title = initialTitle ?: when (mode) {
            Mode.LISTEN -> ctx.getString(R.string.receiving)
            Mode.BROADCAST -> ctx.getString(R.string.broadcasting)
            else -> ctx.getString(R.string.send_files)
        }
        root.addView(
            W.toolbar(ctx, title, null, onBack = { leave() }, trailing = listOf(overflow))
        )

        radar = RadarView(ctx)
        radar.visibility = View.GONE

        val scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        body = W.column(ctx, 0, 14)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        actionBar = W.row(ctx, 8, 8)
        actionBar.background = ThemeColors.cardDrawable(ctx, 1, 18f)
        root.addView(actionBar)

        onShown()
        return root
    }

    override fun onShown() {
        engine.items.observe(itemsObserver)
        engine.peers.observe(peersObserver)
        engine.phase.observe(phaseObserver)
        engine.transportKind.observe(transportObserver)
        engine.events.observe(eventObserver)

        when (mode) {
            Mode.LISTEN -> {
                engine.startListening()
                askDiscoverable()
            }
            Mode.BROADCAST -> Unit
            else -> engine.startDiscovery()
        }
        if (pendingUris.isNotEmpty()) {
            engine.queueUris(pendingUris, batchId, null)
            pendingUris = emptyList()
        }
        rebuild()
    }

    override fun onHidden() {
        engine.items.unobserve(itemsObserver)
        engine.peers.unobserve(peersObserver)
        engine.phase.unobserve(phaseObserver)
        engine.transportKind.unobserve(transportObserver)
        engine.events.unobserve(eventObserver)
        handler.removeCallbacks(discoverableTick)
        // Minimising never ends a session - it keeps running in the foreground service.
        Log.info("Transfer screen minimised - sessions continue in the background")
    }

    override fun onBackPressed(): Boolean {
        leave()
        return true
    }

    private fun leave() {
        engine.stopDiscovery()
        (activity as? MainActivity)?.closePushed()
    }

    // ------------------------------------------------------------------ events

    private fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.PeerConnected -> {
                SoundFx.connected(activity)
                Ui.toast(root, activity.getString(R.string.connected_to, event.peer.name, TransportKind.label(event.transport)))
                rebuild()
            }
            is EngineEvent.PeerDisconnected -> {
                if (event.reason != "USER_END") Ui.toast(root, "${event.name}: ${event.reason}")
                rebuild()
            }
            is EngineEvent.BatchCompleted -> {
                if (!event.summary.contains("0 failed")) SoundFx.failed(activity) else SoundFx.success(activity)
                val title = activity.getString(R.string.batch_complete)
                Ui.summaryDialog(
                    activity, title, event.summary, emptyList(),
                    lines = listOf(
                        if (event.received) activity.getString(R.string.receiving)
                        else activity.getString(R.string.sending_title)
                    ) + " \u00b7 " + event.peerName
                )
                rebuild()
            }
            is EngineEvent.GroupCompleted -> {
                SoundFx.success(activity)
                showGroupComplete(event)
                rebuild()
            }
            is EngineEvent.GroupSummary -> {
                // aggregate dialog in addition to the per-recipient screen
                if (event.message.isNotBlank()) Ui.info(activity, activity.getString(R.string.broadcast_complete), event.message)
            }
            is EngineEvent.ConsentRequested -> Unit // handled by MainActivity
            is EngineEvent.Message -> Ui.toast(root, event.text)
            is EngineEvent.ItemFinished -> rebuild()
        }
    }

    /** Sender-side "Complete" screen: card + Delivered-to list + 3-cell stats grid. */
    private fun showGroupComplete(event: EngineEvent.GroupCompleted) {
        val g = engine.group.value
        val peers = g?.peerIds?.size ?: event.outcomes.size
        val filesEach = g?.let { grp ->
            engine.items.value.filter { it.groupId == grp.groupId }.map { it.displayName }.distinct().size
        } ?: 0
        val totalMb = "%.0f".format(
            java.util.Locale.US,
            (g?.let { grp -> engine.items.value.filter { it.groupId == grp.groupId }.sumOf { it.size } } ?: 0L) / 1048576.0
        )
        val delivered = event.outcomes.joinToString("\n") { o ->
            val mark = if (o.ok) "\u2713" else "\u2717"
            "$mark ${o.peerName}  -  ${o.sent} sent" +
                (if (o.failed > 0) ", ${o.failed} failed" else "") +
                (if (o.skipped > 0) ", ${o.skipped} skipped" else "") +
                "  -  avg ${"%.1f".format(java.util.Locale.US, o.avgSpeedBps / 1048576.0)} MB/s"
        }
        Ui.summaryDialog(
            activity,
            activity.getString(R.string.broadcast_complete),
            activity.getString(R.string.all_n_phones_verified, peers),
            listOf(
                "$peers" to activity.getString(R.string.stats_phones),
                "$filesEach" to activity.getString(R.string.stats_files_each),
                totalMb to activity.getString(R.string.stats_total_mb)
            ),
            lines = listOf(activity.getString(R.string.delivered_to)) + delivered.split("\n")
        )
    }

    // ------------------------------------------------------------------ render

    private fun rebuild() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        val phase = engine.phase.value
        val items = engine.items.value
        val active = items.filter { it.direction == Direction.SENDING && !it.isTerminal }
        val incoming = items.filter { it.direction == Direction.RECEIVING && !it.isTerminal }
        val group = engine.group.value

        when {
            group != null && items.any { it.groupId == group.groupId } -> {
                body.addView(broadcastingView(group.groupId, items))
            }
            phase == SessionPhase.CONNECTED || active.isNotEmpty() || incoming.isNotEmpty() -> {
                if (incoming.isNotEmpty()) {
                    body.addView(receivingView(incoming))
                } else {
                    body.addView(sendingView(items))
                }
            }
            phase == SessionPhase.SEARCHING_RECEIVE -> body.addView(listeningView())
            else -> body.addView(searchingView())
        }
        rebuildActionBar()
    }

    // ---- searching -----------------------------------------------------------

    private fun searchingView(): View {
        val ctx = activity
        val col = W.column(ctx)

        radar.setMode(RadarView.Mode.DISCOVERY)
        radar.setPeers(engine.peers.value)
        radar.visibility = View.VISIBLE
        col.addView(radar.detach(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, D.dp(ctx, 190f)))

        val head = W.column(ctx)
        head.setGravity(Gravity.CENTER)
        head.addView(W.label(ctx, ctx.getString(R.string.searching_for_devices), 17f, ThemeColors.text(ctx), bold = true, gravity = Gravity.CENTER))
        head.addView(W.label(ctx, ctx.getString(R.string.searching_sub), 12.5f, ThemeColors.text2(ctx), gravity = Gravity.CENTER))
        // One sentence about the side of the transfer this screen is not showing: the receiver
        // has to be waiting (and discoverable) for the sender to see it at all.
        if (engine.transportKind.value == TransportKind.NEARBY) {
            head.addView(W.gap(ctx, 6))
            head.addView(W.label(ctx,
                ctx.getString(if (mode == Mode.LISTEN) R.string.nearby_receiver_hint else R.string.nearby_sender_hint),
                12f, ThemeColors.text2(ctx), gravity = Gravity.CENTER))
        }
        col.addView(head)
        col.addView(W.gap(ctx, 16))

        val transportCard = W.card(ctx)
        transportCard.addView(W.infoRow(ctx, ctx.getString(R.string.label_transport),
            TransportKind.label(engine.transportKind.value)))
        col.addView(transportCard)
        col.addView(W.gap(ctx, 8))

        val problem = engine.nearbyProblem()
        if (problem != null) {
            col.addView(TransportBanner.build(activity, problem) {
                engine.setTransport(TransportKind.LAN)
                rebuild()
            })
            col.addView(W.gap(ctx, 8))
        }

        val queueCard = W.card(ctx)
        val queue = engine.items.value.filter { !it.isTerminal }
        val totalBytes = queue.sumOf { it.size }
        queueCard.addView(W.infoRow(ctx, ctx.getString(R.string.label_queue),
            if (queue.isEmpty()) "0 files" else "${queue.size} files \u00b7 ${Fmt.size(totalBytes)}"))
        col.addView(queueCard)
        col.addView(W.gap(ctx, 16))

        val peersRow = W.row(ctx)
        peersRow.addView(W.label(ctx, "DISCOVERED", 11f, ThemeColors.muted(ctx), bold = true, mono = true))
        peersRow.addView(W.spacer(ctx))
        val refresh = W.label(ctx, "Refresh", 12f, ThemeColors.accentStart(ctx), bold = true)
        refresh.pad(8, 6, 8, 6)
        refresh.isClickable = true
        refresh.onClick { engine.startDiscovery() }
        peersRow.addView(refresh)
        col.addView(peersRow)
        col.addView(W.gap(ctx, 6))

        val peers = engine.peers.value.filter { it.deviceId != "manual:" }
        if (peers.isEmpty()) {
            col.addView(W.emptyState(ctx, R.drawable.ic_nav_connect, ctx.getString(R.string.empty_peers),
                ctx.getString(R.string.pairing_hint)))
        } else {
            for (p in peers) col.addView(peerRow(p))
        }

        col.addView(W.gap(ctx, 12))
        val buttons = W.row(ctx)
        val qr = W.outlineButton(ctx, ctx.getString(R.string.scan_qr), R.drawable.ic_qr)
        qr.onClick { activity.startActivity(Intent(activity, QrScanActivity::class.java)) }
        val manual = W.outlineButton(ctx, ctx.getString(R.string.manual_ip), R.drawable.ic_link)
        manual.onClick { askManualIp() }
        val lp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp1.setMargins(0, 0, D.dp(ctx, 6f), 0)
        val lp2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp2.setMargins(D.dp(ctx, 6f), 0, 0, 0)
        buttons.addView(qr, lp1)
        buttons.addView(manual, lp2)
        col.addView(buttons)
        col.addView(W.gap(ctx, 16))

        if (!Di.prefs(ctx).tipShown("pairing")) {
            Di.prefs(ctx).markTip("pairing")
            Ui.tip(activity, ctx.getString(R.string.tip_once_pairing))
        }
        return col
    }

    private fun listeningView(): View {
        val ctx = activity
        val col = W.column(ctx)
        col.addView(W.emptyState(ctx, R.drawable.ic_receive_down, ctx.getString(R.string.listening_for_senders),
            ctx.getString(R.string.listening_sub)))
        val card = W.card(ctx)
        card.addView(W.infoRow(ctx, ctx.getString(R.string.label_broadcasting_as), Di.prefs(ctx).deviceName))
        card.addView(W.divider(ctx))
        card.addView(W.infoRow(ctx, ctx.getString(R.string.label_transport), "Wi-Fi LAN \u00b7 BT"))
        card.addView(W.divider(ctx))
        card.addView(W.infoRow(ctx, ctx.getString(R.string.label_port, com.morsecode.app.BuildConfig.TCP_PORT), ""))
        col.addView(card)
        col.addView(W.gap(ctx, 12))
        // receiving shows any queued send-back files too
        val items = engine.items.value
        if (items.isNotEmpty()) {
            col.addView(W.label(ctx, "Sending back \u00b7 ${items.count { !it.isTerminal }} files",
                13f, ThemeColors.text2(ctx), bold = true))
            col.addView(W.gap(ctx, 6))
            for (i in items.take(12)) col.addView(fileRow(i))
        }
        return col
    }

    // ---- single peer send / receive -----------------------------------------

    private fun sendingView(items: List<TransferItem>): View {
        val ctx = activity
        val col = W.column(ctx)
        val peerId = items.firstOrNull()?.peerId
        val peer = engine.knownPeer(peerId ?: "") ?: engine.activePeers.value.firstOrNull()
        if (peer != null) col.addView(peerCard(peer, items))

        val active = items.filter { !it.isTerminal && it.direction == Direction.SENDING }
        if (active.isEmpty() && items.isEmpty()) {
            col.addView(W.emptyState(ctx, R.drawable.ic_send_up, ctx.getString(R.string.empty_peers),
                ctx.getString(R.string.pairing_hint)))
            return col
        }

        val header = W.row(ctx, 8, 0)
        header.addView(W.label(ctx, "Sending \u00b7 ${items.size} files", 13.5f, ThemeColors.text(ctx), bold = true))
        header.addView(W.spacer(ctx))
        if (active.isNotEmpty()) {
            val pauseAll = W.pill(ctx, ctx.getString(R.string.pause_all), ThemeColors.accentStart(ctx))
            pauseAll.isClickable = true
            pauseAll.onClick { engine.pauseAll() }
            header.addView(pauseAll)
        }
        col.addView(header)
        col.addView(W.gap(ctx, 6))
        for (i in items.take(40)) {
            col.addView(fileRow(i))
            col.addView(W.gap(ctx, 6))
        }
        col.addView(W.gap(ctx, 8))
        col.addView(summaryCard(items, false))
        return col
    }

    private fun receivingView(items: List<TransferItem>): View {
        val ctx = activity
        val col = W.column(ctx)
        val peer = engine.knownPeer(items.firstOrNull()?.peerId ?: "") ?: engine.activePeers.value.firstOrNull()
        if (peer != null) col.addView(peerCard(peer, items))

        col.addView(W.row(ctx, 8, 0).apply {
            addView(W.label(ctx, "Receiving \u00b7 ${items.size} files", 13.5f, ThemeColors.text(ctx), bold = true))
            addView(W.spacer(ctx))
            val pauseAll = W.pill(ctx, ctx.getString(R.string.pause_all), ThemeColors.accentStart(ctx))
            pauseAll.isClickable = true
            pauseAll.onClick { engine.pauseAll() }
            addView(pauseAll)
        })
        col.addView(W.gap(ctx, 6))
        for (i in items.take(40)) {
            col.addView(fileRow(i))
            col.addView(W.gap(ctx, 6))
        }
        col.addView(W.gap(ctx, 8))
        col.addView(summaryCard(items, true))
        return col
    }

    private fun peerCard(peer: DiscoveredPeer, items: List<TransferItem>): View {
        val ctx = activity
        val card = W.card(ctx)
        val row = W.row(ctx)
        row.addView(W.avatar(ctx, peer.initial, ThemeColors.avatarAccentFor(peer.deviceId), 44))
        row.addView(W.hgap(ctx, 10))
        val who = W.column(ctx)
        who.addView(W.label(ctx, peer.name, 15.5f, ThemeColors.text(ctx), bold = true))
        who.addView(W.label(ctx, "Connected \u00b7 Phone \u00b7 ${TransportKind.label(peer.transport)}",
            11.5f, ThemeColors.text2(ctx), mono = true))
        row.addView(who, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(W.pill(ctx, TransportKind.label(peer.transport).uppercase(), ThemeColors.accentStart(ctx)))
        card.addView(row)
        if (items.isNotEmpty()) card.addView(W.gap(ctx, 6))
        return card
    }

    /** One transfer row: icon, name, size, live bytes/speed, state badge and per-row actions. */
    private fun fileRow(item: TransferItem): View {
        val ctx = activity
        val card = W.card(ctx, 1, 16f, 12)
        val top = W.row(ctx)
        top.addView(W.icon(ctx, iconFor(item), 20, iconTint(item)))
        top.addView(W.hgap(ctx, 10))
        val col = W.column(ctx)
        col.addView(W.label(ctx, item.displayName, 14f, ThemeColors.text(ctx), bold = true))
        val meta = when {
            item.state == ItemState.IN_PROGRESS ->
                "${Fmt.sizeShort(item.bytesTransferred)} / ${Fmt.sizeShort(item.totalBytes)} \u00b7 ${Fmt.speed(item.speedBps)} MB/s"
            item.state == ItemState.PAUSED -> "${Fmt.size(item.size)} \u00b7 ${ctx.getString(R.string.resume_at, Fmt.sizeShort(item.resumeOffset))}"
            item.state == ItemState.QUEUED && item.waitingForSlot -> "${Fmt.size(item.size)} \u00b7 ${ctx.getString(R.string.waiting_to_start)}"
            item.state == ItemState.QUEUED -> "${Fmt.size(item.size)} \u00b7 ${ctx.getString(R.string.waiting)}"
            item.lastError != null -> "${Fmt.size(item.size)} \u00b7 ${item.lastError}"
            else -> Fmt.size(item.size)
        }
        col.addView(W.label(ctx, meta, 11.5f, ThemeColors.text2(ctx), mono = true))
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(badge(item))
        card.addView(top)

        if (item.state == ItemState.IN_PROGRESS || item.state == ItemState.PAUSED) {
            card.addView(W.gap(ctx, 8))
            val p = W.progressBar(ctx)
            p.setProgress(item.progress)
            card.addView(p)
        }

        val actions = rowActions(item)
        if (actions.isNotEmpty()) {
            card.addView(W.gap(ctx, 8))
            val r = W.row(ctx)
            for (a in actions) {
                r.addView(a)
                r.addView(W.hgap(ctx, 6))
            }
            card.addView(r)
        }
        return card
    }

    private fun rowActions(item: TransferItem): List<View> {
        val ctx = activity
        val out = ArrayList<View>()
        val isSend = item.direction == Direction.SENDING
        when (item.state) {
            ItemState.IN_PROGRESS -> {
                if (isSend) out.add(smallButton(R.string.pause) { engine.pauseItem(item) })
                out.add(smallButton(R.string.retry) { engine.retryItem(item) })
                out.add(smallDanger(R.string.cancel) { engine.cancelItem(item) })
            }
            ItemState.QUEUED -> {
                out.add(smallButton(R.string.send_now) { engine.resumeItem(item) })
                out.add(smallDanger(R.string.remove) { engine.removeItem(item) })
            }
            ItemState.PAUSED -> {
                out.add(smallButton(R.string.resume) { engine.resumeItem(item) })
                out.add(smallDanger(R.string.remove) { engine.removeItem(item) })
            }
            ItemState.FAILED, ItemState.CANCELLED -> {
                out.add(smallButton(R.string.retry) { engine.retryItem(item) })
                out.add(smallDanger(R.string.remove) { engine.removeItem(item) })
            }
            else -> {
                out.add(smallButton(R.string.retry) { engine.retryItem(item) })
            }
        }
        return out
    }

    private fun smallButton(labelRes: Int, body: () -> Unit): View {
        val ctx = activity
        val v = W.pill(ctx, ctx.getString(labelRes), ThemeColors.accentStart(ctx))
        v.isClickable = true
        v.onClick { body() }
        return v
    }

    private fun smallDanger(labelRes: Int, body: () -> Unit): View {
        val ctx = activity
        val v = W.pill(ctx, ctx.getString(labelRes), ThemeColors.Red)
        v.isClickable = true
        v.onClick { body() }
        return v
    }

    private fun badge(item: TransferItem): View {
        val ctx = activity
        val receiving = item.direction == Direction.RECEIVING
        val label: String
        val color: Int
        when (item.state) {
            ItemState.QUEUED -> {
                label = ctx.getString(if (receiving) R.string.state_queue else R.string.state_queued)
                color = ThemeColors.MutedDark
            }
            ItemState.IN_PROGRESS -> {
                label = ctx.getString(if (receiving) R.string.state_receiving else R.string.state_sending)
                color = ThemeColors.accentStart(ctx)
            }
            ItemState.COMPLETED -> {
                label = ctx.getString(R.string.state_done)
                color = ThemeColors.Green
            }
            ItemState.SKIPPED -> {
                label = ctx.getString(R.string.state_skipped)
                color = ThemeColors.Blue
            }
            ItemState.PAUSED -> {
                label = ctx.getString(R.string.state_paused)
                color = ThemeColors.Ember2
            }
            ItemState.CANCELLED -> {
                label = ctx.getString(R.string.state_cancelled)
                color = ThemeColors.Red
            }
            ItemState.FAILED -> {
                label = ctx.getString(R.string.state_failed)
                color = ThemeColors.Red
            }
        }
        val v = W.pill(ctx, label, color)
        if (item.state == ItemState.COMPLETED && receiving) {
            val row = W.row(ctx)
            row.addView(v)
            row.addView(W.hgap(ctx, 4))
            row.addView(W.pill(ctx, ctx.getString(R.string.crc_verified), ThemeColors.Green))
            return row
        }
        return v
    }

    private fun iconFor(item: TransferItem): Int = when {
        item.mime.startsWith("image/") -> R.drawable.ic_image
        item.mime.startsWith("video/") -> R.drawable.ic_video
        item.mime.startsWith("audio/") -> R.drawable.ic_music
        item.mime.contains("zip") -> R.drawable.ic_folder
        else -> R.drawable.ic_file
    }

    private fun iconTint(item: TransferItem): Int = when {
        item.mime.startsWith("image/") -> ThemeColors.FileImg
        item.mime.startsWith("video/") -> ThemeColors.Purple
        item.mime.startsWith("audio/") -> ThemeColors.Ember2
        item.mime.contains("zip") -> ThemeColors.FileZip
        else -> ThemeColors.FileDoc
    }

    /** Exact bottom summary card phrasing from the spec. */
    private fun summaryCard(items: List<TransferItem>, receiving: Boolean): View {
        val ctx = activity
        val stats = engine.summaryStats()
        val card = W.outlinedCard(ctx, true, 18f, 16)
        val title = if (stats.queued + stats.paused + stats.sending + stats.receiving > 0)
            ctx.getString(R.string.batch_in_progress) else ctx.getString(R.string.batch_complete)
        card.addView(W.label(ctx, title, 15.5f, ThemeColors.text(ctx), bold = true))
        val subtitle = if (title == ctx.getString(R.string.batch_in_progress)) {
            if (receiving) ctx.getString(R.string.summary_receiving, stats.receiving, stats.queued, stats.paused,
                Fmt.mbSpeed(stats.avgSpeedBps))
            else ctx.getString(R.string.summary_sending, stats.sending, stats.queued, stats.paused,
                Fmt.mbSpeed(stats.avgSpeedBps))
        } else {
            if (receiving) ctx.getString(R.string.summary_received, stats.completed, stats.failed, stats.skipped,
                Fmt.mbSpeed(stats.avgSpeedBps))
            else ctx.getString(R.string.summary_deliveries, stats.completed, stats.failed, stats.skipped,
                Fmt.mbSpeed(stats.avgSpeedBps))
        }
        card.addView(W.label(ctx, subtitle, 12.5f, ThemeColors.text2(ctx)))
        card.addView(W.gap(ctx, 10))
        val grid = W.row(ctx)
        val total = items.sumOf { it.totalBytes }
        val sent = items.sumOf { it.bytesTransferred }
        grid.addView(W.statCell(ctx, "${items.size}", R.string.stats_files_each))
        grid.addView(W.statCell(ctx, "%.0f".format(java.util.Locale.US, total / 1048576.0), R.string.stats_total_mb))
        grid.addView(W.statCell(ctx, "%.0f".format(java.util.Locale.US, sent / 1048576.0),
            if (receiving) R.string.stats_mb_sent else R.string.stats_mb_sent))
        card.addView(grid)
        return card
    }

    // ---- broadcast -----------------------------------------------------------

    private fun broadcastingView(groupId: String, items: List<TransferItem>): View {
        val ctx = activity
        val g = engine.group.value
        val col = W.column(ctx)
        val peers = g?.peerIds?.mapNotNull { engine.knownPeer(it) } ?: emptyList()
        val groupItems = items.filter { it.groupId == groupId }
        val fileNames = groupItems.map { it.displayName }.distinct()

        if (peers.size >= 2) {
            val toggle = W.row(ctx, 6, 0)
            val topologyBtn = W.pill(ctx, ctx.getString(R.string.show_topology), ThemeColors.accentStart(ctx))
            topologyBtn.isClickable = true
            topologyBtn.onClick {
                radar.visibility = if (radar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                radar.setMode(RadarView.Mode.TOPOLOGY)
                radar.setBroadcast(peers, perPeerProgress(groupItems))
            }
            toggle.addView(topologyBtn)
            col.addView(toggle)
            radar.setMode(RadarView.Mode.TOPOLOGY)
            radar.setBroadcast(peers, perPeerProgress(groupItems))
            col.addView(radar.detach(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, D.dp(ctx, 168f)))
            col.addView(W.gap(ctx, 10))
        }

        for (name in fileNames) {
            val perFile = groupItems.filter { it.displayName == name }
            val first = perFile.first()
            val card = W.card(ctx, 1, 16f, 12)
            val top = W.row(ctx)
            top.addView(W.icon(ctx, iconFor(first), 20, iconTint(first)))
            top.addView(W.hgap(ctx, 10))
            val c = W.column(ctx)
            c.addView(W.label(ctx, name, 14f, ThemeColors.text(ctx), bold = true))
            c.addView(W.label(ctx, Fmt.size(first.size), 11.5f, ThemeColors.text2(ctx), mono = true))
            top.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(badge(first))
            card.addView(top)
            card.addView(W.gap(ctx, 8))
            // per-peer mini progress strip, one small bar per selected peer
            for (peer in peers) {
                val item = perFile.firstOrNull { it.peerId == peer.deviceId }
                val row = W.row(ctx, 3, 0)
                val av = W.avatar(ctx, peer.initial, ThemeColors.avatarAccentFor(peer.deviceId), 20)
                row.addView(av)
                row.addView(W.hgap(ctx, 8))
                val p = ProgressView(ctx, 4)
                val progress = item?.progress ?: 0f
                p.setProgress(progress)
                p.layoutParams = LinearLayout.LayoutParams(0, D.dp(ctx, 4f), 1f)
                row.addView(p)
                val label = when {
                    item == null -> "\u2013"
                    item.waitingForSlot -> ctx.getString(R.string.waiting_to_start)
                    item.state == ItemState.IN_PROGRESS -> "${(progress * 100).toInt()}%"
                    item.state == ItemState.COMPLETED -> "\u2713"
                    item.state == ItemState.SKIPPED -> "skip"
                    item.state == ItemState.FAILED -> "\u2717"
                    item.state == ItemState.PAUSED -> ctx.getString(R.string.state_paused)
                    else -> "\u2013"
                }
                row.addView(W.label(ctx, label, 10.5f,
                    ThemeColors.accentStart(ctx), mono = true))
                card.addView(row)
            }
            col.addView(card)
            col.addView(W.gap(ctx, 8))
        }

        // bottom summary card + 3-cell stats grid
        val stats = engine.summaryStats()
        val card = W.outlinedCard(ctx, true, 18f, 16)
        val head = W.row(ctx)
        head.addView(W.icon(ctx, R.drawable.ic_list, 16, ThemeColors.accentStart(ctx)))
        head.addView(W.hgap(ctx, 8))
        head.addView(W.label(ctx, ctx.getString(R.string.broadcasting_to_n_phones, peers.size),
            15f, ThemeColors.text(ctx), bold = true))
        card.addView(head)
        card.addView(W.gap(ctx, 4))
        val broadcasting = groupItems.count { it.state == ItemState.IN_PROGRESS }
        val queued = groupItems.count { it.state == ItemState.QUEUED }
        card.addView(W.label(ctx, ctx.getString(R.string.broadcast_progress_sub, broadcasting, queued,
            Fmt.mbSpeed(stats.avgSpeedBps)), 12.5f, ThemeColors.text2(ctx)))
        card.addView(W.gap(ctx, 10))
        val grid = W.row(ctx)
        grid.addView(W.statCell(ctx, "${peers.size}", R.string.stats_peers))
        grid.addView(W.statCell(ctx, "%.0f".format(java.util.Locale.US, groupItems.sumOf { it.totalBytes } / 1048576.0),
            R.string.stats_total_mb))
        grid.addView(W.statCell(ctx, "%.0f".format(java.util.Locale.US, groupItems.sumOf { it.bytesTransferred } / 1048576.0),
            R.string.stats_mb_sent))
        card.addView(grid)
        col.addView(card)

        if (peers.size < DeviceTier.maxParallelPeers) {
            col.addView(W.gap(ctx, 8))
            col.addView(W.label(ctx,
                "This device streams ${DeviceTier.maxParallelPeers} peers at a time - the rest wait for a slot.",
                11.5f, ThemeColors.muted(ctx)))
        }
        return col
    }

    private fun perPeerProgress(items: List<TransferItem>): Map<String, Float> {
        val map = HashMap<String, Float>()
        for (i in items) {
            val current = map[i.peerId] ?: 0f
            map[i.peerId] = maxOf(current, i.progress)
        }
        return map
    }

    // ---- action bar ----------------------------------------------------------

    private fun rebuildActionBar() {
        val ctx = activity
        actionBar.removeAllViews()
        val items = engine.items.value
        val hasActive = items.any { it.state == ItemState.IN_PROGRESS || it.state == ItemState.QUEUED }
        val paused = items.any { it.state == ItemState.PAUSED }
        val group = engine.group.value
        val broadcasting = group != null && items.any { it.groupId == group.groupId }

        actionBar.addView(action("+", R.string.choose_files, accent = true) { pickFiles() })
        actionBar.addView(action("\u2261", R.string.queue_label) { showQueueSheet() })
        if (hasActive) {
            actionBar.addView(action("\u2225", R.string.pause_all) { engine.pauseAll() })
        } else if (paused) {
            actionBar.addView(action("\u2225", R.string.resume_all) { engine.resumeAll() })
        }
        actionBar.addView(action("\u2013", R.string.minimize) { leave() })
        actionBar.addView(actionEnd(if (broadcasting) R.string.end else R.string.end) {
            if (broadcasting) {
                Ui.confirm(activity, ctx.getString(R.string.end_broadcast_title),
                    ctx.getString(R.string.end_broadcast_body),
                    ctx.getString(R.string.end_session_confirm), { endAll() },
                    ctx.getString(R.string.cancel), null, destructive = true)
            } else {
                Ui.confirm(activity, ctx.getString(R.string.end_session_title),
                    ctx.getString(R.string.end_session_body),
                    ctx.getString(R.string.end_session_confirm), { endAll() },
                    ctx.getString(R.string.cancel), null, destructive = true)
            }
        })
    }

    private fun endAll() {
        engine.endSession("USER_END")
        engine.group.set(null)
        engine.stopDiscovery()
        Log.info("Session closed by peer - reason=USER_END")
        activity.finish()
    }

    private fun action(glyph: String, labelRes: Int, accent: Boolean = false, body: () -> Unit): View {
        val ctx = activity
        val col = W.column(ctx)
        col.setGravity(Gravity.CENTER)
        col.pad(2, 6, 2, 6)
        col.setBackgroundColor(if (accent) ThemeColors.withAlpha(ThemeColors.accentStart(ctx), 0.14f) else ThemeColors.Transparent)
        col.addView(W.label(ctx, glyph, 17f,
            if (accent) ThemeColors.accentStart(ctx) else ThemeColors.text(ctx), bold = true, gravity = Gravity.CENTER))
        col.addView(W.label(ctx, ctx.getString(labelRes), 10f,
            if (accent) ThemeColors.accentStart(ctx) else ThemeColors.text2(ctx), gravity = Gravity.CENTER))
        col.isClickable = true
        col.onClick { body() }
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return col
    }

    private fun actionEnd(labelRes: Int, body: () -> Unit): View {
        val ctx = activity
        val col = W.column(ctx)
        col.setGravity(Gravity.CENTER)
        col.pad(2, 6, 2, 6)
        col.addView(W.label(ctx, "\u00d7", 19f, ThemeColors.Red, bold = true, gravity = Gravity.CENTER))
        col.addView(W.label(ctx, ctx.getString(labelRes), 10f, ThemeColors.Red, gravity = Gravity.CENTER))
        col.isClickable = true
        col.onClick { body() }
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return col
    }

    // ---- interactions --------------------------------------------------------

    private fun pickFiles() {
        Permissions.request(activity, Permissions.storage(activity), Permissions.REQ_STORAGE)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(intent, REQ_PICK)
        } catch (t: Throwable) {
            Ui.toast(root, "No file picker available")
        }
    }

    fun onPickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        batchId = engine.newBatchId()
        val peer = engine.activePeers.value.firstOrNull()
            ?: engine.peers.value.firstOrNull { it.connected }
        engine.queueUris(uris, batchId, peer)
        Ui.toast(root, "${uris.size} file(s) queued")
        rebuild()
    }

    private fun askManualIp() {
        Ui.prompt(activity, activity.getString(R.string.manual_ip_title),
            activity.getString(R.string.manual_ip_hint), "", activity.getString(R.string.connect)) { value ->
            val host = value.substringBefore(':').trim()
            val port = value.substringAfter(':', com.morsecode.app.BuildConfig.TCP_PORT.toString()).toIntOrNull()
                ?: com.morsecode.app.BuildConfig.TCP_PORT
            if (host.isBlank()) {
                Ui.toast(root, activity.getString(R.string.invalid_address))
            } else {
                engine.connectManual(host, port)
            }
        }
    }

    private fun peerRow(peer: DiscoveredPeer): View {
        val ctx = activity
        val card = W.card(ctx, 1, 16f, 12)
        val row = W.row(ctx)
        row.addView(W.avatar(ctx, peer.initial, ThemeColors.avatarAccentFor(peer.deviceId), 40))
        row.addView(W.hgap(ctx, 10))
        val col = W.column(ctx)
        col.addView(W.label(ctx, peer.name, 14.5f, ThemeColors.text(ctx), bold = true))
        val detail = if (peer.transport == TransportKind.NEARBY) "Bluetooth \u00b7 Nearby"
        else "${peer.address} \u00b7 ${TransportKind.label(peer.transport)}"
        col.addView(W.label(ctx, detail, 11.5f, ThemeColors.text2(ctx), mono = true))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // LAN + 2..4 peers selected => Broadcast; a 5th selection is blocked with a toast.
        if (engine.isLanActive()) {
            val cb = android.widget.CheckBox(ctx)
            val on = selected.containsKey(peer.deviceId)
            cb.isChecked = on
            cb.isClickable = false
            row.addView(cb)
            row.addView(W.hgap(ctx, 8))
            cb.visibility = View.GONE // the whole row toggles; the checkbox shows state
            card.isClickable = true
            card.onClick { toggleSelection(peer) }
            if (on) {
                card.background = ThemeColors.tagDrawable(ctx, ThemeColors.accentStart(ctx), 16f)
            }
        }

        val connect = W.pill(ctx, ctx.getString(R.string.connect), ThemeColors.accentStart(ctx))
        connect.isClickable = true
        connect.onClick { engine.connectTo(peer) }
        row.addView(connect)
        card.addView(row)
        return card
    }

    private fun toggleSelection(peer: DiscoveredPeer) {
        if (selected.containsKey(peer.deviceId)) {
            selected.remove(peer.deviceId)
        } else {
            if (selected.size >= 4) {
                Ui.toast(root, activity.getString(R.string.max_four_devices))
                return
            }
            selected[peer.deviceId] = peer
        }
        engine.selectedForBroadcast.set(selected.keys.toList())
        rebuild()
        if (selected.size >= 2) showSendToNBar()
    }

    private fun showSendToNBar() {
        val ctx = activity
        Ui.confirm(activity, ctx.getString(R.string.send_to_n_devices, selected.size),
            selected.values.joinToString(", ") { it.name }, ctx.getString(R.string.send_files), {
                val peers = selected.values.toList()
                engine.queueBroadcast(pickQueuedOrAsk(peers), peers)
                modeBroadcast()
            }, ctx.getString(R.string.cancel), null)
    }

    /** Broadcast sends the current queue; if there is nothing queued we ask for files first. */
    private fun pickQueuedOrAsk(peers: List<DiscoveredPeer>): List<Uri> {
        val queued = engine.items.value.filter { it.state == ItemState.QUEUED || it.state == ItemState.PAUSED }
            .mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }
        if (queued.isNotEmpty()) return queued
        pickFiles()
        return emptyList()
    }

    private fun modeBroadcast() {
        Ui.toast(root, activity.getString(R.string.broadcasting_to_n_phones, selected.size))
    }

    private fun showQueueSheet() {
        val ctx = activity
        val items = engine.items.value
        val title = ctx.getString(R.string.queue_title, items.count { !it.isTerminal })
        val sheet = Ui.bottomSheet(activity, ctx.getString(R.string.sending_title), title)
        if (items.isEmpty()) {
            sheet.add(W.emptyState(ctx, R.drawable.ic_list, ctx.getString(R.string.empty_state_files_hint)))
        }
        for (item in items.take(30)) {
            val card = W.card(ctx, 1, 14f, 10)
            val top = W.row(ctx)
            top.addView(badge(item))
            top.addView(W.hgap(ctx, 8))
            top.addView(W.label(ctx, item.displayName, 13.5f, ThemeColors.text(ctx), bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(top)
            card.addView(W.label(ctx, when (item.state) {
                ItemState.IN_PROGRESS -> "${Fmt.sizeShort(item.bytesTransferred)} / ${Fmt.sizeShort(item.totalBytes)} \u00b7 ${Fmt.speed(item.speedBps)} MB/s"
                ItemState.PAUSED -> "${Fmt.size(item.size)} \u00b7 ${ctx.getString(R.string.resume_at, Fmt.sizeShort(item.resumeOffset))}"
                ItemState.QUEUED -> "${Fmt.size(item.size)} \u00b7 ${ctx.getString(R.string.waiting)}"
                else -> "${Fmt.size(item.size)} \u00b7 ${item.lastError ?: ""}"
            }, 11.5f, ThemeColors.text2(ctx), mono = true))
            if (item.state == ItemState.IN_PROGRESS) {
                val p = W.progressBar(ctx)
                p.setProgress(item.progress)
                card.addView(W.gap(ctx, 6))
                card.addView(p)
            }
            val acts = W.row(ctx)
            acts.pad(0, 8, 0, 0)
            for (a in rowActions(item)) {
                acts.addView(a)
                acts.addView(W.hgap(ctx, 6))
            }
            card.addView(acts)
            sheet.add(card)
            sheet.add(W.gap16(ctx))
        }
        sheet.show()
    }

    // ------------------------------------------------------- being findable

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Classic Bluetooth inquiry only reports phones that are discoverable, and an open RFCOMM
     * server socket does not make a phone discoverable. The receiving side therefore has to ask,
     * or the sender searches forever and the receiving device never appears.
     *
     * Android caps the window at 300 s, so the request is repeated while the screen is up
     * (a request made while already discoverable is a no-op and shows no dialog).
     */
    private fun askDiscoverable() {
        if (mode != Mode.LISTEN) return
        if (engine.transportKind.value != TransportKind.NEARBY) return
        if (engine.nearbyProblem() != null) return
        val already = Compat.isDiscoverable()
        Compat.requestDiscoverable(activity, 300)
        if (!already) Log.info("Asking the system to make this phone discoverable for 5 minutes")
        handler.removeCallbacks(discoverableTick)
        handler.postDelayed(discoverableTick, 240_000)
    }

    private val discoverableTick = object : Runnable {
        override fun run() = askDiscoverable()
    }

    override fun refresh() = rebuild()

    private fun showOverflow() {
        val ctx = activity
        val options = arrayListOf(
            ctx.getString(R.string.switch_transport),
            ctx.getString(R.string.connection_doctor),
            ctx.getString(R.string.clear)
        )
        Ui.pick(activity, ctx.getString(R.string.settings), options, 0) { index ->
            when (index) {
                0 -> {
                    engine.switchTransport()
                    Ui.toast(root, "Transport: ${TransportKind.label(engine.transportKind.value)}")
                }
                1 -> ConnectionDoctor.show(activity)
                2 -> engine.clearFinished()
            }
        }
    }

    companion object {
        const val REQ_PICK = 7801
    }
}

/** Small helper so the sheet layout code stays readable. */
private fun W.gap16(ctx: android.content.Context): View = W.gap(ctx, 8)
