package com.morsecode.app.core.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.morsecode.app.BuildConfig
import com.morsecode.app.core.model.DiscoveredPeer
import com.morsecode.app.core.model.ItemState
import com.morsecode.app.core.model.TransferItem
import com.morsecode.app.core.model.TransportKind
import com.morsecode.app.core.storage.Destinations
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.util.Compat
import com.morsecode.app.core.util.Paths
import com.morsecode.app.core.util.Workers
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * The zero-infrastructure transport: **Bluetooth** (the Nearby path).
 *
 * It plays exactly the role Google's Nearby Connections plays in the product spec - no shared
 * router, no internet, discovery over the radio - and speaks the same JSON control vocabulary
 * and the same MLNK chunk framing as [LanTransport], so the engine cannot tell them apart.
 *
 * Product-spec UI copy that stays true: "uses Bluetooth/Wi-Fi Direct, requires no shared
 * network, does not use the router, Broadcast is unavailable here."
 *
 * Deviations from the spec letter, documented in README.md:
 *  - Google Play Services Nearby Connections is not linked (the offline toolchain cannot
 *    resolve external Maven artifacts and Nexus/Google Maven are unreachable). The transport
 *    implements the same behaviour over RFCOMM instead. Play Services presence is still
 *    probed and reported by the Connection Doctor.
 *  - The per-file payload travels on its own RFCOMM connection, mirroring the LAN design.
 */
class NearbyTransport(
    private val ctx: Context,
    private val host: SessionHost,
    private val deviceId: String,
    private val deviceName: String,
    private val onPeerSeen: (DiscoveredPeer) -> Unit
) : Transport {

    override val kind: String = TransportKind.NEARBY

    private val serviceUuid: UUID = UUID.fromString("6a1d4b3f-2c58-4e77-8f11-2b0f4a9c7d61")
    private val serviceName = "MorseCode"

    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var receiver: BroadcastReceiver? = null
    private val bonded = java.util.concurrent.ConcurrentHashMap<String, BluetoothDevice>()

    val sessions = java.util.concurrent.ConcurrentHashMap<String, StreamSession>()

    override fun beacon(): String = "Bluetooth discovery active"

    private fun adapter(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (t: Throwable) {
        null
    }

    fun available(): Boolean = adapter()?.isEnabled == true

    // ---------------------------------------------------------------- lifecycle

    override fun start() {
        if (running) return
        val ad = adapter()
        if (ad == null || !ad.isEnabled) {
            Log.warn("Bluetooth is off - Nearby transport unavailable")
            return
        }
        if (!Compat.checkSelf(ctx, android.Manifest.permission.BLUETOOTH)) {
            Log.warn("Bluetooth permission missing")
            return
        }
        running = true
        startServer(ad)
        startDiscovery(ad)
        Log.info("Nearby (Bluetooth) transport started as $deviceName")
    }

    override fun stop() {
        running = false
        try { serverSocket?.close() } catch (ignored: Throwable) {}
        serverSocket = null
        try { receiver?.let { ctx.unregisterReceiver(it) } } catch (ignored: Throwable) {}
        receiver = null
        try { adapter()?.cancelDiscovery() } catch (ignored: Throwable) {}
        for ((_, s) in sessions) s.close("Transport stopped")
        sessions.clear()
        Log.info("Nearby (Bluetooth) transport stopped")
    }

    private fun startServer(ad: BluetoothAdapter) {
        acceptThread = Thread({
            try {
                val ss = ad.listenUsingRfcommWithServiceRecord(serviceName, serviceUuid)
                serverSocket = ss
                while (running) {
                    val socket = try { ss.accept() } catch (t: Throwable) { break } ?: continue
                    Workers.runNamed("mc-bt-in") { handleIncoming(socket) }
                }
            } catch (t: Throwable) {
                Log.warn("Bluetooth server stopped: ${t.message}")
            }
        }, "mc-bt-server").apply { isDaemon = true; start() }
    }

    private fun startDiscovery(ad: BluetoothAdapter) {
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            @Suppress("DEPRECATION")
                            val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            if (d != null) reportDevice(d)
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            if (running) {
                                try { ad.startDiscovery() } catch (ignored: Throwable) {}
                            }
                        }
                    }
                }
            }
            receiver = r
            ctx.registerReceiver(r, filter)
            // paired devices are usable immediately, even before a discovery sweep finishes
            @Suppress("DEPRECATION")
            for (d in ad.bondedDevices ?: emptySet()) reportDevice(d)
            try { ad.startDiscovery() } catch (ignored: Throwable) {}
        } catch (t: Throwable) {
            Log.warn("Bluetooth discovery unavailable: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun reportDevice(d: BluetoothDevice) {
        try {
            val name = try { d.name } catch (t: Throwable) { null } ?: return
            if (name.isBlank()) return
            val id = "bt:" + d.address.replace(":", "")
            bonded[d.address] = d
            val peer = DiscoveredPeer(
                deviceId = id,
                name = name,
                transport = TransportKind.NEARBY,
                address = d.address,
                port = 0
            )
            peer.signal = 2
            onPeerSeen(peer)
        } catch (t: Throwable) {
            // some devices throw on name lookups during discovery - ignore
        }
    }

    // ---------------------------------------------------------------- sessions

    private fun handleIncoming(socket: BluetoothSocket) {
        try {
            val remote = socket.remoteDevice
            val addr = remote?.address ?: ""
            val peerName = try { remote?.name } catch (t: Throwable) { null } ?: "Bluetooth device"
            val peerId = "bt:" + addr.replace(":", "")
            val link = BtLink(socket)
            val s = StreamSession(ctx, host, peerId, peerName, addr, link, deviceId, deviceName, TransportKind.NEARBY)
            // A Bluetooth data link is created per file, so the first line tells us which it is.
            val first = RawLineReader(link.input).readLine() ?: run { link.close(); return }
            val o = Wire.parse(first) ?: run { link.close(); return }
            if (o.optString("t") == Wire.T_DATA) {
                sessions[peerId]?.receiveData(link, o)
                return
            }
            if (o.optString("t") != Wire.T_HELLO) {
                link.close(); return
            }
            sessions.remove(peerId)?.close("Replaced by a new session")
            s.onMeta = { meta ->
                s.send(prepareIncoming(s, meta, meta.optString("batch"), meta.optString("group").ifBlank { null }))
            }
            val requester = DiscoveredPeer(
                deviceId = peerId, name = peerName, transport = TransportKind.NEARBY, address = addr
            )
            if (!host.requestConsent(requester, s)) {
                s.send(Wire.reject("REJECTED"))
                link.close()
                return
            }
            s.send(Wire.accept(deviceId, deviceName))
            s.startControlReader()
            sessions[peerId] = s
            host.onSessionEstablished(s)
        } catch (t: Throwable) {
            Log.warn("Incoming Bluetooth session failed: ${t.message}")
            try { socket.close() } catch (ignored: Throwable) {}
        }
    }

    override fun connect(peer: DiscoveredPeer, host: SessionHost): StreamSession? {
        val device = bonded[peer.address]
            ?: try { adapter()?.getRemoteDevice(peer.address) } catch (t: Throwable) { null }
            ?: return null
        return try {
            val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
            try { adapter()?.cancelDiscovery() } catch (ignored: Throwable) {}
            socket.connect()
            val s = StreamSession(ctx, host, peer.deviceId, peer.name, peer.address,
                BtLink(socket), deviceId, deviceName, TransportKind.NEARBY)
            if (!s.performHandshake()) {
                s.closeFromPeer("Handshake refused")
                return null
            }
            s.onMeta = { meta ->
                s.send(prepareIncoming(s, meta, meta.optString("batch"), meta.optString("group").ifBlank { null }))
            }
            s.startControlReader()
            sessions[peer.deviceId] = s
            Log.info("Session established with ${peer.name} (Bluetooth)")
            s
        } catch (t: Throwable) {
            Log.error("Bluetooth connect failed: ${t.message}")
            null
        }
    }

    // -------------------------------------------------- incoming payload links

    private fun prepareIncoming(s: StreamSession, meta: JSONObject, batchId: String, groupId: String?): String {
        val fileId = meta.optString("fileId")
        val name = meta.optString("name").ifBlank { "file" }
        val size = meta.optLong("size")
        val mime = meta.optString("mime").ifBlank { Paths.guessMime(name) }
        val sha = meta.optString("sha").ifBlank { null }
        val dir = Destinations.receivedDir(ctx, com.morsecode.app.di.Di.saf(ctx).defaultFolder())
        val target = Destinations.resolve(ctx, dir, name, size, sha, com.morsecode.app.di.Di.prefs(ctx).conflictPolicy)
        if (target.alreadyPresent) return Wire.ack(fileId, target.existingLength, "already")

        val item = TransferItem(
            id = fileId,
            batchId = batchId.ifBlank { "-" },
            groupId = groupId,
            peerId = s.peerId,
            peerName = s.peerName,
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
        s.attachIncoming(StreamSession.ReceiveTarget(item, target.partFile, target.finalFile, target.existingLength))
        host.onIncomingFile(s, item)
        return Wire.ack(fileId, target.existingLength, "ok")
    }
}

/** BluetoothSocket as a [Link]. */
class BtLink(private val socket: BluetoothSocket) : Link {
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override fun close() = socket.close()
}
