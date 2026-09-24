package com.morsecode.app.core.network

import android.content.Context
import android.net.wifi.WifiManager
import com.morsecode.app.BuildConfig
import com.morsecode.app.core.model.DiscoveredPeer
import com.morsecode.app.core.model.ItemState
import com.morsecode.app.core.model.TransferItem
import com.morsecode.app.core.storage.Destinations
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.util.Net
import com.morsecode.app.core.util.Paths
import com.morsecode.app.core.util.Workers
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi LAN transport.
 *
 *  discovery : UDP broadcast on 33457, announce every 1200 ms (multicast lock held)
 *  control   : TCP 33456, one connection per peer, UTF-8 JSON lines
 *  data      : TCP 33456, one connection per file per peer, MLNK-framed chunks
 *
 * A sender may hold several independent sessions at once - that is exactly how Broadcast
 * fans out to up to four phones without any protocol change.
 */
class LanTransport(
    private val ctx: Context,
    private val host: SessionHost,
    private val deviceId: String,
    private val deviceName: String,
    private val onPeerSeen: (DiscoveredPeer) -> Unit
) : Transport {

    override val kind: String = com.morsecode.app.core.model.TransportKind.LAN

    @Volatile private var running = false
    private var beacon: Thread? = null
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    val sessions = ConcurrentHashMap<String, LanSession>()
    private val pendingMeta = ConcurrentHashMap<String, PendingIncoming>()

    class PendingIncoming(
        val session: LanSession,
        val item: TransferItem,
        val partFile: File,
        val finalFile: File
    )

    override fun beacon(): String = "UDP broadcast :${BuildConfig.UDP_DISCOVERY_PORT} sent"

    // ---------------------------------------------------------------- lifecycle

    override fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        startServer()
        startDiscovery()
        Log.info("LAN transport started - TCP :${BuildConfig.TCP_PORT}, beacon :${BuildConfig.UDP_DISCOVERY_PORT}")
    }

    override fun stop() {
        running = false
        releaseMulticastLock()
        try { server?.close() } catch (ignored: Throwable) {}
        server = null
        try { udp?.close() } catch (ignored: Throwable) {}
        udp = null
        for ((_, s) in sessions) s.close("Transport stopped")
        sessions.clear()
        pendingMeta.clear()
        Log.info("LAN transport stopped")
    }

    private fun acquireMulticastLock() {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (multicastLock == null) {
                multicastLock = wm.createMulticastLock("morsecode-discovery").apply { setReferenceCounted(false) }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
                Log.info("Multicast lock acquired")
            }
        } catch (t: Throwable) {
            Log.warn("Multicast lock unavailable: ${t.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (ignored: Throwable) {
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun startDiscovery() {
        beacon = Thread({
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 1500
                socket.bind(InetSocketAddress(BuildConfig.UDP_DISCOVERY_PORT))
                udp = socket
                var counter = 0
                val buf = ByteArray(4096)
                while (running) {
                    val payload = announce().toByteArray(Charsets.UTF_8)
                    try {
                        val target = Net.broadcastAddress(Net.localIp())
                            ?: InetAddress.getByName("255.255.255.255")
                        socket.send(DatagramPacket(payload, payload.size, target, BuildConfig.UDP_DISCOVERY_PORT))
                        if (counter % 20 == 0) Log.info(beacon())
                    } catch (t: Throwable) {
                        Log.warn("Beacon failed: ${t.message}")
                    }
                    counter++
                    // drain whatever arrived while we waited
                    val deadline = System.currentTimeMillis() + 1200
                    while (running && System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            handleAnnounce(String(packet.data, 0, packet.length, Charsets.UTF_8),
                                packet.address?.hostAddress ?: "")
                        } catch (t: Throwable) {
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.warn("Discovery stopped: ${t.message}")
            }
        }, "mc-discovery").apply { isDaemon = true; start() }
    }

    private fun announce(): String = JSONObject().apply {
        put("t", "MC_ANNOUNCE")
        put("app", Wire.APP)
        put("uuid", BuildConfig.APP_UUID)
        put("id", deviceId)
        put("name", deviceName)
        put("port", BuildConfig.TCP_PORT)
    }.toString()

    private fun handleAnnounce(text: String, address: String) {
        val o = Wire.parse(text) ?: return
        if (o.optString("ua", "") == deviceId) return
        val id = o.optString("id")
        if (id.isBlank() || id == deviceId) return
        if (o.optString("app") != Wire.APP) return
        val peer = DiscoveredPeer(
            deviceId = id,
            name = o.optString("name").ifBlank { "Android phone" },
            transport = com.morsecode.app.core.model.TransportKind.LAN,
            address = address,
            port = o.optInt("port", BuildConfig.TCP_PORT)
        )
        peer.signal = signalHint()
        onPeerSeen(peer)
    }

    private fun signalHint(): Int {
        val rssi = com.morsecode.app.core.util.Compat.rssi(ctx)
        return when {
            rssi >= -55 -> 3
            rssi >= -75 -> 2
            else -> 1
        }
    }

    // ---------------------------------------------------------------- server

    private fun startServer() {
        Workers.runNamed("mc-lan-server") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(BuildConfig.TCP_PORT))
                server = ss
                Log.info("TCP control :${BuildConfig.TCP_PORT} listening")
                while (running) {
                    val socket = try {
                        ss.accept()
                    } catch (t: Throwable) {
                        break
                    }
                    Workers.runNamed("mc-in-${socket.inetAddress?.hostAddress}") { handleSocket(socket) }
                }
            } catch (t: Throwable) {
                Log.error("LAN server stopped: ${t.message}")
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        var session: LanSession? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val ins = socket.getInputStream()
            // CRITICAL: the first line is always read byte-by-byte from the raw stream.
            val first = RawLineReader(ins).readLine()
            if (first == null) {
                socket.close(); return
            }
            val o = Wire.parse(first)
            if (o == null) {
                socket.close(); return
            }
            when (o.optString("t")) {
                Wire.T_HELLO -> {
                    val remoteId = o.optString("id")
                    val remoteName = o.optString("name").ifBlank { "Android phone" }
                    val addr = socket.inetAddress?.hostAddress ?: ""
                    val existing = sessions.remove(remoteId)
                    existing?.closeFromPeer("Replaced by a new session")
                    val s = LanSession(ctx, host, remoteId, remoteName, addr, SocketLink(socket), deviceId, deviceName)
                    session = s
                    val requester = DiscoveredPeer(
                        deviceId = remoteId, name = remoteName,
                        transport = com.morsecode.app.core.model.TransportKind.LAN,
                        address = addr, port = BuildConfig.TCP_PORT
                    )
                    Log.info("TCP control :${BuildConfig.TCP_PORT} connected - $remoteName")
                    val accepted = host.requestConsent(requester, s)
                    if (!accepted) {
                        s.send(Wire.reject("REJECTED"))
                        Log.info("Session rejected - reason=USER_REJECT")
                        try { socket.close() } catch (ignored: Throwable) {}
                        return
                    }
                    s.send(Wire.accept(deviceId, deviceName))
                    sessions[remoteId] = s
                    wireMeta(s)
                    s.startControlReader()
                    host.onSessionEstablished(s)
                }
                Wire.T_DATA -> {
                    // Data connections are matched to their session by peer address.
                    val addr = socket.inetAddress?.hostAddress ?: ""
                    val owner = sessions.values.firstOrNull { it.address == addr }
                    if (owner == null) {
                        Log.warn("Data connection from unknown peer $addr")
                        socket.close()
                        return
                    }
                    owner.receiveData(SocketLink(socket), o)
                    return
                }
                else -> {
                    socket.close()
                }
            }
        } catch (t: Throwable) {
            Log.warn("Inbound socket error: ${t.message}")
            try { socket.close() } catch (ignored: Throwable) {}
        }
    }

    /** Receiver side of the protocol: a META line turns into a prepared destination + ACK. */
    private fun wireMeta(s: LanSession) {
        s.onMeta = { meta ->
            val ack = prepareIncoming(s, meta, meta.optString("batch"), meta.optString("group").ifBlank { null })
            s.send(ack)
        }
    }

    // ---------------------------------------------------------------- outgoing

    override fun connect(peer: DiscoveredPeer, host: SessionHost): LanSession? {
        return try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(peer.address, peer.port), 12_000)
            val s = LanSession(ctx, host, peer.deviceId, peer.name, peer.address, SocketLink(socket), deviceId, deviceName)
            if (!s.performHandshake()) {
                s.closeFromPeer("Handshake refused")
                Log.info("Connect refused by ${peer.name}")
                return null
            }
            wireMeta(s)
            s.startControlReader()
            sessions[peer.deviceId] = s
            Log.info("Session established with ${peer.name} (LAN)")
            s
        } catch (t: Throwable) {
            Log.error("Connect failed to ${peer.name}: ${t.message}")
            null
        }
    }

    fun sessionFor(peerId: String): LanSession? = sessions[peerId]

    // -------------------------------------------------- incoming file plumbing

    /**
     * Called from the control plane (a META line). Applies the conflict policy, answers with the
     * resume offset, and registers the ReceiveTarget the data connection will write into.
     */
    fun prepareIncoming(
        session: LanSession,
        meta: JSONObject,
        batchId: String,
        groupId: String?
    ): String {
        val fileId = meta.optString("fileId")
        val name = meta.optString("name").ifBlank { "file" }
        val size = meta.optLong("size")
        val mime = meta.optString("mime").ifBlank { Paths.guessMime(name) }
        val sha = meta.optString("sha").ifBlank { null }
        val resume = meta.optLong("resume")

        val dir = Destinations.receivedDir(ctx, com.morsecode.app.di.Di.saf(ctx).defaultFolder())
        val target = Destinations.resolve(ctx, dir, name, size, sha, com.morsecode.app.di.Di.prefs(ctx).conflictPolicy)
        if (target.alreadyPresent) {
            Log.info("Already present: $name - replying skipped")
            return Wire.ack(fileId, target.existingLength, "already")
        }
        // conflict policy: skip / rename / overwrite (default rename)
        val finalFile = target.finalFile

        if (target.partFile.exists() && target.partFile.length() > 0) {
            Log.info("Resume available for $name at ${target.partFile.length()} bytes")
        }
        val item = TransferItem(
            id = fileId,
            batchId = batchId.ifBlank { "-" },
            groupId = groupId,
            peerId = session.peerId,
            peerName = session.peerName,
            direction = com.morsecode.app.core.model.Direction.RECEIVING,
            displayName = name,
            uri = "file://${target.finalFile.absolutePath}",
            mime = mime,
            size = size
        )
        item.totalBytes = size
        item.resumeOffset = target.existingLength
        item.sha256 = sha
        item.state = ItemState.IN_PROGRESS
        session.attachIncoming(StreamSession.ReceiveTarget(item, target.partFile, target.finalFile, target.existingLength))
        pendingMeta[fileId] = PendingIncoming(session, item, target.partFile, target.finalFile)
        host.onIncomingFile(session, item)
        return Wire.ack(fileId, target.existingLength, "ok")
    }

    fun pending(fileId: String): PendingIncoming? = pendingMeta[fileId]

    fun clearPending(fileId: String) {
        pendingMeta.remove(fileId)
    }

    fun sessionCount(): Int = sessions.size
}
