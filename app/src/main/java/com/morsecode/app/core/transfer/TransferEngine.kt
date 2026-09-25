package com.morsecode.app.core.transfer

import android.content.Context
import android.net.Uri
import com.morsecode.app.BuildConfig
import com.morsecode.app.core.model.DiscoveredPeer
import com.morsecode.app.core.model.Direction
import com.morsecode.app.core.model.GroupHistoryEntry
import com.morsecode.app.core.model.HistoryEntry
import com.morsecode.app.core.model.ItemState
import com.morsecode.app.core.model.PeerOutcome
import com.morsecode.app.core.model.SendResult
import com.morsecode.app.core.model.TransferGroup
import com.morsecode.app.core.model.TransferItem
import com.morsecode.app.core.model.TransportKind
import com.morsecode.app.core.network.LanTransport
import com.morsecode.app.core.network.NearbyTransport
import com.morsecode.app.core.network.SessionHost
import com.morsecode.app.core.network.Transport
import com.morsecode.app.core.network.TransportSession
import com.morsecode.app.core.storage.Destinations
import com.morsecode.app.core.util.DeviceTier
import com.morsecode.app.core.util.Event
import com.morsecode.app.core.util.Integrity
import com.morsecode.app.core.util.Paths
import com.morsecode.app.core.util.State
import com.morsecode.app.core.util.Workers
import com.morsecode.app.di.Di
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Session lifecycle as the UI sees it. */
enum class SessionPhase { IDLE, SEARCHING_SEND, SEARCHING_RECEIVE, CONNECTED, BATCH_DONE }

/** Everything the UI can react to. */
sealed class EngineEvent {
    class PeerConnected(val peer: DiscoveredPeer, val transport: String) : EngineEvent()
    class PeerDisconnected(val peerId: String, val name: String, val reason: String) : EngineEvent()
    class ConsentRequested(val peer: DiscoveredPeer, val transport: String, val session: TransportSession, val answer: (Boolean) -> Unit) : EngineEvent()
    class BatchCompleted(val peerId: String, val peerName: String, val batchId: String, val summary: String, val received: Boolean) : EngineEvent()
    class GroupCompleted(val groupId: String, val outcomes: List<PeerOutcome>) : EngineEvent()
    class GroupSummary(val message: String) : EngineEvent()
    class ItemFinished(val item: TransferItem) : EngineEvent()
    class Message(val text: String) : EngineEvent()
}

/**
 * TransferEngine - the single source of truth for queue state, session state and group state.
 * UI fragments observe the [State]s below and render; they never hold transfer state themselves.
 *
 * Invariants enforced here (see the product spec):
 *   INV-1 a worker never hangs
 *   INV-2 pause/cancel on one file never blocks another (per peer, per item)
 *   INV-3 losing a session pauses that peer's items only, and they re-queue on reconnect
 */
class TransferEngine(private val ctx: Context) : SessionHost {

    // ---- observable state --------------------------------------------------

    val items = State<List<TransferItem>>(emptyList())
    val peers = State<List<DiscoveredPeer>>(emptyList())
    val phase = State(SessionPhase.IDLE)
    val activePeers = State<List<DiscoveredPeer>>(emptyList())
    val group = State<TransferGroup?>(null)
    val events = Event<EngineEvent>()
    val transportKind = State(Di.prefs(ctx).lastTransport)

    /** Set by the visible Activity so consent can always be asked; null when nothing is showing. */
    @Volatile var consentHandler: ((DiscoveredPeer, String, TransportSession, (Boolean) -> Unit) -> Unit)? = null

    val selectedForBroadcast = State<List<String>>(emptyList())

    // ---- internals ---------------------------------------------------------

    private val masters = ConcurrentHashMap<String, Master>()
    private val batches = ConcurrentHashMap<String, MutableSet<String>>()   // batchId -> item ids
    private val groupMembers = ConcurrentHashMap<String, MutableSet<String>>() // groupId -> item ids
    private val shaCache = ConcurrentHashMap<String, String?>()              // source key -> sha (once per file, reused for every peer)
    private val slotLimits = ConcurrentHashMap<String, Semaphore>()
    private val slotHolders = ConcurrentHashMap<String, MutableSet<String>>()
    private val summaryArmed = ConcurrentHashMap<String, AtomicBoolean>()
    private val summaries = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "mc-summary").apply { isDaemon = true }
    }
    private val beacon = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "mc-beacon").apply { isDaemon = true }
    }

    @Volatile private var lan: LanTransport? = null
    @Volatile private var nearby: NearbyTransport? = null
    @Volatile private var searching = false
    private val deviceId: String = Di.prefs(ctx).let { "mc-" + Integer.toHexString(System.identityHashCode(it)) } +
        "-" + BuildConfig.APP_UUID.take(8)

    init {
        Log.attach(ctx)
        emit()
    }

    // ---- shared identity ---------------------------------------------------

    fun deviceName(): String = Di.prefs(ctx).deviceName
    fun deviceId(): String = stableDeviceId()

    private var cachedId: String? = null
    private fun stableDeviceId(): String {
        cachedId?.let { return it }
        val sp = ctx.getSharedPreferences("morsecode_id", Context.MODE_PRIVATE)
        var id = sp.getString("id", null)
        if (id == null) {
            id = "mc-" + UUID.randomUUID().toString().replace("-", "").take(12)
            sp.edit().putString("id", id).apply()
            Log.info("Device id assigned")
        }
        cachedId = id
        return id
    }

    // ---- transports --------------------------------------------------------

    private fun ensureLan(): LanTransport {
        var t = lan
        if (t == null) {
            t = LanTransport(ctx, this, stableDeviceId(), deviceName()) { seen -> onPeerSeen(seen) }
            lan = t
        }
        return t
    }

    private fun ensureNearby(): NearbyTransport {
        var t = nearby
        if (t == null) {
            t = NearbyTransport(ctx, this, stableDeviceId(), deviceName()) { seen -> onPeerSeen(seen) }
            nearby = t
        }
        return t
    }

    fun currentTransport(): Transport =
        if (transportKind.value == TransportKind.NEARBY) ensureNearby() else ensureLan()

    fun isLanActive(): Boolean = transportKind.value == TransportKind.LAN

    fun startDiscovery() {
        startSelected()
        searching = true
        if (phase.value == SessionPhase.IDLE || phase.value == SessionPhase.BATCH_DONE) {
            phase.set(SessionPhase.SEARCHING_SEND)
        }
        Log.info("Multicast lock acquired")
    }

    fun startListening() {
        startSelected()
        searching = true
        phase.set(SessionPhase.SEARCHING_RECEIVE)
        Log.info("Listening for senders on :${BuildConfig.TCP_PORT}")
    }

    /**
     * Start the transport the user selected - not always the LAN one.
     *
     * These two entry points used to call `ensureLan().start()` unconditionally, so a phone whose
     * selected transport was Nearby started a Wi-Fi search and never touched Bluetooth: the
     * sender saw "Nearby" on screen and an empty list, and nothing was listening on the receiver.
     */
    private fun startSelected() {
        val t = currentTransport()
        t.start()
        if (t.isRunning) return
        if (transportKind.value == TransportKind.NEARBY) {
            Log.warn("Nearby (Bluetooth) did not start (${nearbyProblem() ?: "unknown"}) - Wi-Fi LAN is carrying the session")
            ensureLan().start()
        }
    }

    /**
     * Why the Nearby transport cannot run right now, or null when it can - or when it is not the
     * selected transport, in which case the question does not apply. The Connect and Transfer
     * screens turn the token into a sentence and offer the fix.
     */
    fun nearbyProblem(): String? =
        if (transportKind.value == TransportKind.NEARBY) ensureNearby().problem() else null

    fun setTransport(kind: String) {
        if (kind == transportKind.value) return
        transportKind.set(kind)
        Di.prefs(ctx).lastTransport = kind
        // Peers belong to the transport that found them; a Wi-Fi peer must not linger in the
        // list for two minutes after the user switched to Bluetooth and look like a nearby one.
        peers.set(emptyList())
        val t = currentTransport()
        t.start()
        Log.info("Transport switched to ${TransportKind.label(kind)} (running=${t.isRunning})")
        if (!t.isRunning && kind == TransportKind.NEARBY) {
            Log.warn("Nearby (Bluetooth) could not start: ${ensureNearby().problem() ?: "unknown"}")
        }
    }

    fun switchTransport() = setTransport(
        if (transportKind.value == TransportKind.LAN) TransportKind.NEARBY else TransportKind.LAN
    )

    /** The Bluetooth side came up after the user was asked to turn it on. */
    fun onBluetoothEnabled() {
        if (transportKind.value == TransportKind.NEARBY) {
            Log.info("Bluetooth is on - starting the Nearby transport")
            startDiscovery()
        }
    }

    fun stopDiscovery() {
        searching = false
        if (phase.value == SessionPhase.SEARCHING_SEND || phase.value == SessionPhase.SEARCHING_RECEIVE) {
            phase.set(if (masters.isEmpty()) SessionPhase.IDLE else SessionPhase.CONNECTED)
        }
    }

    private fun onPeerSeen(peer: DiscoveredPeer) {
        val list = peers.value.toMutableList()
        val existing = list.indexOfFirst { it.deviceId == peer.deviceId }
        if (existing >= 0) {
            val old = list[existing]
            old.name = peer.name
            old.address = peer.address
            old.transport = peer.transport
            old.lastSeen = System.currentTimeMillis()
            old.signal = peer.signal
        } else {
            list.add(peer)
            Log.info("Peer discovered: ${peer.name}")
            Di.prefs(ctx).rememberDevice(peer.deviceId, peer.name, peer.transport)
        }
        val now = System.currentTimeMillis()
        peers.set(list.filter { now - it.lastSeen < 120_000 })
    }

    fun forgetPeer(peerId: String) {
        peers.set(peers.value.filterNot { it.deviceId == peerId })
    }

    fun knownPeer(peerId: String): DiscoveredPeer? = peers.value.firstOrNull { it.deviceId == peerId }

    // ---- queueing ----------------------------------------------------------

    /** Every picker session is one batch. */
    fun newBatchId(): String = UUID.randomUUID().toString()

    fun queueUris(uris: List<Uri>, batchId: String, peer: DiscoveredPeer?, groupId: String? = null) {
        val target = peer
        val list = items.value.toMutableList()
        for (u in uris.take(DeviceTier.maxConcurrentPickerItems)) {
            val name = Paths.displayName(ctx, u)
            val size = Paths.size(ctx, u)
            val mime = Paths.mime(ctx, u)
            val item = TransferItem(
                batchId = batchId,
                groupId = groupId,
                peerId = target?.deviceId ?: "",
                peerName = target?.name ?: "",
                direction = Direction.SENDING,
                displayName = name,
                uri = u.toString(),
                mime = mime,
                size = size
            )
            item.totalBytes = size
            list.add(item)
        }
        batches.getOrPut(batchId) { java.util.Collections.synchronizedSet(HashSet()) }
            .addAll(list.filter { it.batchId == batchId }.map { it.id })
        publish(list)
        Log.info("Queued ${uris.size} file(s) in batch ${batchId.take(6)}")
    }

    /** Broadcast: one batch, one groupId, up to 4 LAN peers, one TransferItem per file per peer. */
    fun queueBroadcast(uris: List<Uri>, peers: List<DiscoveredPeer>) {
        if (peers.size < 2) {
            queueUris(uris, newBatchId(), peers.firstOrNull())
            return
        }
        val batchId = newBatchId()
        val groupId = UUID.randomUUID().toString()
        val g = TransferGroup(groupId = groupId, batchId = batchId)
        for (p in peers) {
            g.peerIds.add(p.deviceId)
            g.peerNames[p.deviceId] = p.name
        }
        group.set(g)
        slotLimits[groupId] = Semaphore(DeviceTier.maxParallelPeers)
        slotHolders[groupId] = java.util.Collections.synchronizedSet(HashSet<String>())
        for (p in peers) queueUris(uris, batchId, p, groupId)
        Log.info("Broadcast group ${groupId.take(6)}: ${uris.size} files x ${peers.size} peers (max ${DeviceTier.maxParallelPeers} stream at once)")
        phase.set(SessionPhase.CONNECTED)
    }

    fun addFiles(uris: List<Uri>, peer: DiscoveredPeer?, batchId: String) {
        queueUris(uris, batchId, peer)
    }

    /** Re-queues only the failed/skipped files from a Broadcast to the affected peers. */
    fun retryGroup(entry: GroupHistoryEntry) {
        val affected = entry.peerOutcomes.filter { it.failed > 0 || it.skipped > 0 }
        val targets = affected.mapNotNull { knownPeer(it.peerId) }
        val uris = items.value.filter { it.groupId == entry.id && it.state == ItemState.COMPLETED }
            .mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }
        if (targets.isEmpty() || uris.isEmpty()) {
            events.emit(EngineEvent.Message("Nothing to retry - the phones are not nearby any more"))
            return
        }
        Log.info("Retrying ${uris.size} file(s) to ${targets.size} phone(s)")
        queueBroadcast(uris, targets)
    }

    fun itemsFor(peerId: String): List<TransferItem> = items.value.filter { it.peerId == peerId }

    fun activeItems(): List<TransferItem> = items.value.filter {
        it.state == ItemState.QUEUED || it.state == ItemState.IN_PROGRESS || it.state == ItemState.PAUSED
    }

    // ---- per-item actions --------------------------------------------------

    fun pauseItem(item: TransferItem) {
        val m = masters[item.peerId]
        if (item.state == ItemState.IN_PROGRESS && m != null) {
            m.session.pauseOutgoing(item.id)
        }
        item.state = ItemState.PAUSED
        item.resumeOffset = item.bytesTransferred
        item.lastError = null
        publish(items.value)
        Log.info("Paused ${item.displayName}")
    }

    fun resumeItem(item: TransferItem) {
        item.state = ItemState.QUEUED
        item.lastError = null
        publish(items.value)
        masters[item.peerId]?.wake()
    }

    fun cancelItem(item: TransferItem) {
        masters[item.peerId]?.session?.cancelTransfer(item.id)
        item.state = ItemState.CANCELLED
        publish(items.value)
        Log.info("Cancelled ${item.displayName}")
    }

    fun removeItem(item: TransferItem) {
        publish(items.value.filterNot { it.id == item.id })
    }

    fun retryItem(item: TransferItem) {
        item.state = ItemState.QUEUED
        item.retryCount = 0
        item.lastError = null
        item.waitingForSlot = false
        publish(items.value)
        masters[item.peerId]?.wake()
    }

    fun pauseAll() {
        for (i in items.value) {
            if (i.state == ItemState.QUEUED || i.state == ItemState.IN_PROGRESS) pauseItem(i)
        }
    }

    fun resumeAll() {
        for (i in items.value) {
            if (i.state == ItemState.PAUSED) i.state = ItemState.QUEUED
        }
        publish(items.value)
        for ((_, m) in masters) m.wake()
    }

    fun endSession(reason: String = "USER_END") {
        Log.info("Session closed by peer - reason=$reason")
        for ((_, m) in masters) m.close(reason)
        masters.clear()
        for (i in items.value) {
            if (i.state == ItemState.IN_PROGRESS || i.state == ItemState.QUEUED) {
                i.state = ItemState.PAUSED
                i.lastError = "Session ended"
            }
        }
        publish(items.value)
        group.set(null)
        phase.set(if (searching) SessionPhase.SEARCHING_SEND else SessionPhase.IDLE)
    }

    fun clearFinished() {
        publish(items.value.filterNot { it.isTerminal })
    }

    // ---- connection --------------------------------------------------------

    fun connectTo(peer: DiscoveredPeer) {
        Log.info("Connecting to ${peer.name} (${TransportKind.label(peer.transport)})")
        phase.set(SessionPhase.CONNECTED)
        Workers.runNamed("mc-connect") {
            val transport = if (peer.transport == TransportKind.NEARBY) ensureNearby() else ensureLan()
            val session = transport.connect(peer, this)
            if (session == null) {
                events.emit(EngineEvent.Message("Could not connect to ${peer.name}"))
                if (masters.isEmpty()) phase.set(SessionPhase.SEARCHING_SEND)
                return@runNamed
            }
            register(session, peer)
        }
    }

    /** Manual IP:port connect (always offered). */
    fun connectManual(host: String, port: Int) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            events.emit(EngineEvent.Message("Enter an address like 192.168.1.42:33456"))
            return
        }
        val peer = DiscoveredPeer(
            deviceId = "manual:$cleanHost:$port",
            name = "$cleanHost",
            transport = TransportKind.LAN,
            address = cleanHost,
            port = port
        )
        onPeerSeen(peer)
        connectTo(peer)
    }

    private fun register(session: TransportSession, peer: DiscoveredPeer) {
        val existing = masters[session.peerId]
        if (existing != null && existing.session.isAlive) {
            existing.close("Replaced")
        }
        val m = Master(session)
        masters[session.peerId] = m
        peer.connected = true
        activePeers.set(listOf(peer))
        phase.set(SessionPhase.CONNECTED)
        events.emit(EngineEvent.PeerConnected(peer, session.transport))
        Log.info("Connected to ${session.peerName} - transport=${TransportKind.label(session.transport)}")

        // INV-3: a fresh session automatically re-queues this peer's paused sends (best-effort resume).
        var requeued = 0
        val list = items.value
        for (i in list) {
            if (i.peerId == session.peerId && i.direction == Direction.SENDING &&
                (i.state == ItemState.PAUSED || i.state == ItemState.FAILED)
            ) {
                i.state = ItemState.QUEUED
                i.lastError = null
                i.waitingForSlot = false
                requeued++
            }
            if (i.peerId == session.peerId && i.peerId.isEmpty()) i.peerId = session.peerId
        }
        if (requeued > 0) Log.info("Re-queued $requeued file(s) for ${session.peerName}")
        publish(list)
        m.start()
    }

    // ---- SessionHost (callbacks from transports) ---------------------------

    override fun requestConsent(peer: DiscoveredPeer, session: TransportSession): Boolean {
        val handler = consentHandler
        if (handler == null) {
            // Nothing is on screen. Hold the request for up to 30 s in case the user returns,
            // otherwise reject cleanly - a rejected peer gets nothing.
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                if (consentHandler != null) break
                try { Thread.sleep(250) } catch (ignored: Throwable) {}
            }
            val late = consentHandler
            if (late == null) {
                Log.warn("Connection request auto-rejected - app not in the foreground")
                return false
            }
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val answer = AtomicBoolean(false)
        val callback: (Boolean) -> Unit = { accepted ->
            answer.set(accepted)
            latch.countDown()
        }
        (consentHandler ?: handler)?.invoke(peer, session.transport, session, callback)
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        if (answer.get()) {
            onPeerSeen(peer)
            Log.info("Consent accepted for ${peer.name}")
        } else {
            Log.info("Consent rejected for ${peer.name}")
        }
        return answer.get()
    }

    override fun onSessionEstablished(session: TransportSession) {
        val peer = knownPeer(session.peerId) ?: DiscoveredPeer(
            session.peerId, session.peerName, session.transport, session.address
        ).also { onPeerSeen(it) }
        peer.connected = true
        register(session, peer)
    }

    override fun onIncomingFile(session: TransportSession, item: TransferItem) {
        val list = items.value.toMutableList()
        list.removeAll { it.id == item.id }
        list.add(item)
        batches.getOrPut(item.batchId) { java.util.Collections.synchronizedSet(HashSet()) }.add(item.id)
        publish(list)
        Log.info("Incoming: ${item.displayName} (${item.size} bytes) from ${session.peerName}")
    }

    override fun onProgress(session: TransportSession, item: TransferItem) {
        // Progress arrives ~3x/second per file; publish without re-sorting for cheapness.
        items.set(items.value)
    }

    override fun onItemState(session: TransportSession, item: TransferItem, state: ItemState, error: String?) {
        val existing = items.value.firstOrNull { it.id == item.id } ?: item
        existing.state = state
        if (error != null) existing.lastError = error
        if (state == ItemState.COMPLETED) {
            existing.bytesTransferred = existing.totalBytes
            existing.finishedAt = System.currentTimeMillis()
            existing.speedBps = 0
            existing.waitingForSlot = false
            Destinations.indexInMediaStore(ctx, java.io.File(Uri.parse(existing.uri).path ?: ""), existing.mime)
            events.emit(EngineEvent.ItemFinished(existing))
        }
        publish(items.value.filterNot { false })
        scheduleBatchSummary(existing.peerId, existing.batchId, existing.direction == Direction.RECEIVING)
    }

    override fun onSessionClosed(session: TransportSession, reason: String) {
        val m = masters.remove(session.peerId) ?: return
        m.halt()
        // INV-3: only this peer's items move; every other peer in a Broadcast keeps streaming.
        val list = items.value
        var paused = 0
        for (i in list) {
            if (i.peerId == session.peerId && i.direction == Direction.SENDING &&
                (i.state == ItemState.IN_PROGRESS || i.state == ItemState.QUEUED)
            ) {
                i.state = ItemState.PAUSED
                i.lastError = "Connection lost"
                i.resumeOffset = i.bytesTransferred
                paused++
            }
        }
        val peer = peers.value.firstOrNull { it.deviceId == session.peerId }
        peer?.connected = false
        activePeers.set(activePeers.value.filterNot { it.deviceId == session.peerId })
        if (paused > 0) Log.warn("Connection lost to ${session.peerName} - paused $paused file(s)")
        events.emit(EngineEvent.PeerDisconnected(session.peerId, session.peerName, reason))
        publish(list)
        releaseGroupSlot(session.peerId)
        if (masters.isEmpty() && phase.value != SessionPhase.SEARCHING_RECEIVE) {
            phase.set(if (activeItems().isEmpty()) SessionPhase.BATCH_DONE else SessionPhase.SEARCHING_SEND)
        }
    }

    override fun log(level: Int, message: String) {
        when (level) {
            2 -> Log.error(message)
            1 -> Log.warn(message)
            else -> Log.info(message)
        }
    }

    // ---- worker plumbing ---------------------------------------------------

    /**
     * One worker per peer session. It picks the first QUEUED send for that peer,
     * hands it to the session, and repeats. Broadcast simply means several of these
     * run at once - one per peer - with a shared slot limiter on low-tier devices.
     */
    inner class Master(val session: TransportSession) {

        @Volatile private var alive = true
        @Volatile private var thread: Thread? = null
        private val lock = Object()
        private var heldGroup: String? = null

        fun start() {
            if (thread != null) return
            thread = Thread({ loop() }, "mc-send-${session.peerName}").apply { isDaemon = true; start() }
        }

        fun halt() {
            alive = false
            synchronized(lock) { (lock as Object).notifyAll() }
        }

        fun wake() {
            synchronized(lock) { (lock as Object).notifyAll() }
        }

        fun close(reason: String) {
            halt()
            session.close(reason)
        }

        private fun loop() {
            var idleTicks = 0
            while (alive) {
                val next = items.value.firstOrNull {
                    it.peerId == session.peerId && it.direction == Direction.SENDING && it.state == ItemState.QUEUED
                }
                if (next == null) {
                    // Release a Broadcast slot once this peer has nothing left to stream.
                    val held = heldGroup
                    if (held != null && items.value.none {
                            it.groupId == held && it.peerId == session.peerId &&
                                (it.state == ItemState.QUEUED || it.state == ItemState.IN_PROGRESS)
                        }
                    ) {
                        releaseSlot(held)
                        heldGroup = null
                    }
                    idleTicks++
                    if (idleTicks > 240) return   // ~60 s idle: park the worker, the session stays up
                    synchronized(lock) { try { (lock as Object).wait(250) } catch (ignored: Throwable) {} }
                    continue
                }
                idleTicks = 0
                process(next)
            }
        }

        private fun process(item: TransferItem) {
            // Broadcast parallelism cap (low-tier devices stream 2 peers at a time).
            val gid = item.groupId
            if (gid != null && gid != heldGroup) {
                item.waitingForSlot = true
                publish(items.value)
                if (!acquireSlot(gid, session.peerId)) return
                item.waitingForSlot = false
                heldGroup = gid
                publish(items.value)
            }
            if (!alive || !session.isAlive) {
                item.state = ItemState.PAUSED
                item.lastError = "Connection lost"
                publish(items.value)
                return
            }

            val sha = preHash(item)
            item.state = ItemState.IN_PROGRESS
            item.startedAt = System.currentTimeMillis()
            publish(items.value)

            var attempts = 0
            while (alive && session.isAlive) {
                val result = session.sendFile(item, sha)
                when (result) {
                    is SendResult.Completed -> {
                        item.state = ItemState.COMPLETED
                        item.bytesTransferred = item.totalBytes
                        item.finishedAt = System.currentTimeMillis()
                        item.speedBps = 0
                        item.waitingForSlot = false
                        publish(items.value)
                        recordHistory(item, true, null)
                        events.emit(EngineEvent.ItemFinished(item))
                        scheduleBatchSummary(item.peerId, item.batchId, false)
                        return
                    }
                    is SendResult.SkippedAlreadyPresent -> {
                        item.state = ItemState.SKIPPED
                        item.waitingForSlot = false
                        item.lastError = null
                        publish(items.value)
                        recordHistory(item, true, "already present")
                        scheduleBatchSummary(item.peerId, item.batchId, false)
                        return
                    }
                    is SendResult.Paused -> {
                        item.state = ItemState.PAUSED
                        item.resumeOffset = item.bytesTransferred
                        publish(items.value)
                        scheduleBatchSummary(item.peerId, item.batchId, false)
                        return
                    }
                    is SendResult.Cancelled -> {
                        item.state = ItemState.CANCELLED
                        item.waitingForSlot = false
                        publish(items.value)
                        scheduleBatchSummary(item.peerId, item.batchId, false)
                        return
                    }
                    is SendResult.Failed -> {
                        if (!session.isAlive) {
                            item.state = ItemState.PAUSED
                            item.lastError = "Connection lost"
                            item.waitingForSlot = false
                            publish(items.value)
                            return
                        }
                        attempts++
                        item.retryCount = attempts
                        if (attempts <= MAX_AUTO_RETRY) {
                            item.lastError = result.error
                            item.state = ItemState.QUEUED
                            publish(items.value)
                            try { Thread.sleep(RETRY_DELAY_MS) } catch (ignored: Throwable) {}
                        } else {
                            item.state = ItemState.FAILED
                            item.lastError = result.error
                            item.waitingForSlot = false
                            publish(items.value)
                            recordHistory(item, false, result.error)
                            Log.error("Transfer failed: ${item.displayName} - ${result.error}")
                            scheduleBatchSummary(item.peerId, item.batchId, false)
                            return
                        }
                    }
                }
            }
        }

        /**
         * SHA-256 is computed once per source file, then reused for every peer in a Broadcast -
         * a 4-phone group never hashes the same file four times.
         */
        private fun preHash(item: TransferItem): String? {
            if (item.size > DeviceTier.preHashLimit) return null
            val key = "${item.uri}|${item.size}"
            if (shaCache.containsKey(key)) return shaCache[key]
            val sha = try {
                val uri = Uri.parse(item.uri)
                val path = uri.path
                val input = if (uri.scheme == "file" && path != null) java.io.File(path).inputStream()
                else ctx.contentResolver.openInputStream(uri)
                input?.use { Integrity.sha256(it) }
            } catch (t: Throwable) {
                null
            }
            shaCache[key] = sha
            return sha
        }
    }

    // ---- Broadcast slot limiter -------------------------------------------

    private fun acquireSlot(groupId: String, peerId: String): Boolean {
        val sem = slotLimits[groupId] ?: return true
        try {
            sem.acquire()
        } catch (t: Throwable) {
            return false
        }
        slotHolders[groupId]?.add(peerId)
        Log.info("Broadcast slot granted to ${knownPeer(peerId)?.name ?: peerId}")
        return true
    }

    private fun releaseSlot(groupId: String) {
        try {
            val holders = slotHolders[groupId]
            if (holders != null) {
                val stale = ArrayList<String>(holders)
                for (holder in stale) {
                    val stillStreaming = items.value.any {
                        it.groupId == groupId && it.peerId == holder &&
                            (it.state == ItemState.QUEUED || it.state == ItemState.IN_PROGRESS)
                    }
                    if (!stillStreaming) holders.remove(holder)
                }
            }
            slotLimits[groupId]?.release()
            Log.info("Broadcast slot released in group ${groupId.take(6)}")
        } catch (ignored: Throwable) {
        }
    }

    private fun releaseGroupSlot(peerId: String) {
        val gid = group.value?.groupId ?: return
        val holders = slotHolders[gid] ?: return
        if (holders.contains(peerId)) {
            holders.remove(peerId)
            try { slotLimits[gid]?.release() } catch (ignored: Throwable) {}
            Log.info("Broadcast slot freed by disconnecting peer")
        }
    }

    // ---- batch summaries & history ----------------------------------------

    /**
     * Exactly ONE coalesced summary per batch window, ~2.5 s after the last activity.
     * Never one popup per file.
     */
    private fun scheduleBatchSummary(peerId: String, batchId: String, received: Boolean) {
        if (batchId == "-" || batchId.isBlank()) return
        val key = "$peerId|$batchId"
        val armed = summaryArmed.getOrPut(key) { AtomicBoolean(false) }
        if (armed.getAndSet(true)) {
            // re-arm: cancel the pending fire so the summary window slides with the activity
            pendingSummaries[key]?.let { summaries.remove(it) }
        }
        val task: Runnable = com.morsecode.app.core.ui.runnable {
            armed.set(false)
            try {
                fireBatchSummary(peerId, batchId, received)
            } catch (ignored: Throwable) {
            }
        }
        summaries.schedule(task, 2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        pendingSummaries.put(key, task)
    }

    private val pendingSummaries = ConcurrentHashMap<String, Runnable>()

    private fun fireBatchSummary(peerId: String, batchId: String, received: Boolean) {
        val batchItems = items.value.filter { it.peerId == peerId && it.batchId == batchId }
        if (batchItems.isEmpty()) return
        val pending = batchItems.any { it.state == ItemState.QUEUED || it.state == ItemState.IN_PROGRESS }
        if (pending) return
        val doneCount = batchItems.count { it.state == ItemState.COMPLETED }
        val failed = batchItems.count { it.state == ItemState.FAILED }
        val skipped = batchItems.count { it.state == ItemState.SKIPPED }
        val avg = averageSpeed(batchItems)
        val summary = if (received)
            "$doneCount received - $failed failed - $skipped skipped - avg $avg MB/s"
        else
            "$doneCount deliveries - $failed failed - $skipped skipped - avg $avg MB/s"
        val name = batchItems.firstOrNull()?.peerName ?: "peer"
        events.emit(EngineEvent.BatchCompleted(peerId, name, batchId, summary, received))

        for (i in batchItems) {
            when (i.state) {
                ItemState.COMPLETED -> if (i.direction == Direction.SENDING) recordHistory(i, true, null)
                ItemState.SKIPPED -> recordHistory(i, true, "already present")
                ItemState.FAILED -> recordHistory(i, false, i.lastError)
                else -> {}
            }
        }
        finishGroupIfComplete(batchItems.firstOrNull()?.groupId)
    }

    private fun averageSpeed(list: List<TransferItem>): String {
        val speeds = list.filter { it.size > 0 && it.finishedAt > 0 && it.startedAt > 0 }
            .map { it.size.toDouble() / ((it.finishedAt - it.startedAt).coerceAtLeast(1).toDouble() / 1000.0) }
        val avg = if (speeds.isEmpty()) 0.0 else speeds.sum() / speeds.size
        return "%.1f".format(java.util.Locale.US, avg / 1048576.0)
    }

    private fun recordHistory(item: TransferItem, ok: Boolean, error: String?) {
        val entry = HistoryEntry(
            fileName = item.displayName,
            peerName = item.peerName.ifBlank { "Unknown phone" },
            direction = if (item.direction == Direction.SENDING) "sent" else "received",
            size = item.size,
            ok = ok,
            error = error,
            transport = transportKind.value,
            uri = item.uri
        )
        Di.history(ctx).add(entry)
        if (ok) Log.info("Chunk seq verified - ${item.displayName} complete")
    }

    /** A Broadcast records as exactly ONE GroupHistoryEntry - never one row per peer. */
    private fun finishGroupIfComplete(groupId: String?) {
        if (groupId == null) return
        val g = group.value
        if (g == null || g.groupId != groupId || g.completed) return
        val members = items.value.filter { it.groupId == groupId }
        if (members.isEmpty()) return
        if (members.any { it.state == ItemState.QUEUED || it.state == ItemState.IN_PROGRESS || it.state == ItemState.PAUSED }) return

        val outcomes = ArrayList<PeerOutcome>()
        for (peerId in g.peerIds) {
            val peerItems = members.filter { it.peerId == peerId }
            if (peerItems.isEmpty()) continue
            val o = PeerOutcome(
                peerId = peerId,
                peerName = g.peerNames[peerId] ?: peerItems.first().peerName,
                transport = TransportKind.LAN,
                sent = peerItems.count { it.state == ItemState.COMPLETED },
                failed = peerItems.count { it.state == ItemState.FAILED },
                skipped = peerItems.count { it.state == ItemState.SKIPPED }
            )
            o.avgSpeedBps = peerItems.filter { it.startedAt > 0 && it.finishedAt > 0 }
                .map { it.size * 1000 / (it.finishedAt - it.startedAt).coerceAtLeast(1) }
                .average().let { if (it.isNaN()) 0L else it.toLong() }
            val conn = knownPeer(peerId)?.connected == true
            o.outcome = when {
                o.failed > 0 -> "failed"
                !conn -> "connection lost"
                o.skipped == peerItems.size -> "already present"
                else -> "completed"
            }
            outcomes.add(o)
        }
        g.completed = true
        g.finishedAt = System.currentTimeMillis()
        group.set(g)

        val entry = GroupHistoryEntry(
            id = groupId,
            direction = "sent",
            peerOutcomes = outcomes,
            fileCount = members.map { it.displayName }.distinct().size,
            totalBytes = members.sumOf { it.size },
            label = "Broadcast"
        )
        Di.history(ctx).addGroup(entry)
        events.emit(EngineEvent.GroupCompleted(groupId, outcomes))

        // Sender-side aggregate dialog, e.g. "Sent to 3/4 devices - Alice: 12 sent; Bob: 11 sent, 1 failed"
        val okCount = outcomes.count { it.ok }
        val parts = outcomes.joinToString("; ") { o ->
            val bits = ArrayList<String>()
            if (o.sent > 0) bits.add("${o.sent} sent")
            if (o.failed > 0) bits.add("${o.failed} failed")
            if (o.skipped > 0) bits.add("${o.skipped} skipped")
            if (bits.isEmpty()) bits.add(o.outcome)
            "${o.peerName}: ${bits.joinToString(", ")}"
        }
        val message = "Sent to $okCount/${outcomes.size} devices \u2014 $parts"
        events.emit(EngineEvent.GroupSummary(message))
        phase.set(SessionPhase.BATCH_DONE)
        Log.info("Broadcast complete: $message")
    }

    // ---- helpers -----------------------------------------------------------

    private fun publish(list: List<TransferItem>) {
        items.set(ArrayList(list))
        Di.journal(ctx).save(items.value)
    }

    private fun emit() {
        // initial publish so observers have something to render immediately
        items.set(Di.journal(ctx).load().map { it.also { i -> i.state = ItemState.PAUSED } })
        transportKind.set(Di.prefs(ctx).lastTransport)
    }

    fun summaryStats(): TransferStats {
        val list = items.value
        return TransferStats(
            sending = list.count { it.state == ItemState.IN_PROGRESS && it.direction == Direction.SENDING },
            receiving = list.count { it.state == ItemState.IN_PROGRESS && it.direction == Direction.RECEIVING },
            queued = list.count { it.state == ItemState.QUEUED },
            paused = list.count { it.state == ItemState.PAUSED },
            failed = list.count { it.state == ItemState.FAILED },
            skipped = list.count { it.state == ItemState.SKIPPED },
            completed = list.count { it.state == ItemState.COMPLETED },
            totalBytes = list.sumOf { it.totalBytes },
            sentBytes = list.sumOf { it.bytesTransferred },
            avgSpeedBps = list.filter { it.speedBps > 0 }.map { it.speedBps }.average()
                .let { if (it.isNaN()) 0L else it.toLong() }
        )
    }

    class TransferStats(
        val sending: Int, val receiving: Int, val queued: Int, val paused: Int,
        val failed: Int, val skipped: Int, val completed: Int,
        val totalBytes: Long, val sentBytes: Long, val avgSpeedBps: Long
    )

    companion object {
        const val MAX_AUTO_RETRY = 3
        const val RETRY_DELAY_MS = 800L
    }
}
