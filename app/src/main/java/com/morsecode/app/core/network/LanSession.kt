package com.morsecode.app.core.network

import android.content.Context
import android.net.Uri
import com.morsecode.app.core.model.ItemState
import com.morsecode.app.core.model.SendResult
import com.morsecode.app.core.model.TransferItem
import com.morsecode.app.core.storage.Destinations
import com.morsecode.app.core.transfer.Log
import com.morsecode.app.core.util.Integrity
import com.morsecode.app.core.util.Workers
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** A one-shot, thread-safe result slot (this build ships without kotlinx-coroutines). */
class Await<T> {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val signal = lock.newCondition()
    private var done = false
    private var value: T? = null

    fun complete(v: T) {
        lock.lock()
        try {
            if (!done) {
                done = true
                value = v
                signal.signalAll()
            }
        } finally {
            lock.unlock()
        }
    }

    val isDone: Boolean
        get() = lock.withLock2 { done }

    fun await(ms: Long): T? {
        var left = ms
        lock.lock()
        try {
            while (!done) {
                if (left <= 0) return null
                val start = System.currentTimeMillis()
                try {
                    signal.await(left, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (t: Throwable) {
                    return null
                }
                left -= (System.currentTimeMillis() - start)
            }
            return value
        } finally {
            lock.unlock()
        }
    }
}

private inline fun <T> java.util.concurrent.locks.ReentrantLock.withLock2(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** Stream abstraction so LAN (Socket) and Bluetooth (BluetoothSocket) share one session. */
interface Link {
    val input: java.io.InputStream
    val output: java.io.OutputStream
    fun close()
}

class SocketLink(private val socket: Socket) : Link {
    override val input: java.io.InputStream get() = socket.getInputStream()
    override val output: java.io.OutputStream get() = socket.getOutputStream()
    override fun close() = socket.close()
}

/**
 * One control connection to one peer plus the connections opened on top of it for payloads.
 * Instances exist on both sides of a link: the caller (sender) and the callee (receiver).
 * LAN and Bluetooth/Nearby both run over this class - only the [Link] differs.
 */
class StreamSession(
    private val ctx: Context,
    private val host: SessionHost,
    override val peerId: String,
    override val peerName: String,
    override val address: String,
    private val link: Link,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val transportKind: String = com.morsecode.app.core.model.TransportKind.LAN
) : TransportSession {

    override val transport: String = transportKind
    override val peerDetail: String
        get() = "$address \u00b7 " + (if (transportKind == com.morsecode.app.core.model.TransportKind.NEARBY) "BT" else "LAN")

    @Volatile private var alive = true
    @Volatile private var closedReason: String? = null
    override val isAlive: Boolean get() = alive

    private val out: OutputStream = BufferedOutputStream(link.output, 4096)
    private var input: InputStream = link.input
    private val writeLock = Object()

    private val acks = ConcurrentHashMap<String, Await<JSONObject>>()
    private val completions = ConcurrentHashMap<String, Await<SendResult>>()
    private val pausedFiles: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private val cancelledFiles: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private val receiving = ConcurrentHashMap<String, ReceiveTarget>()

    /** Receiver side bookkeeping for one incoming file. */
    class ReceiveTarget(
        val item: TransferItem,
        val partFile: File,
        val finalFile: File,
        val resumeFrom: Long
    ) {
        @Volatile var failed = false
        @Volatile var error: String? = null
    }

    /** Set by the transport: handles a META line (receiver side). */
    var onMeta: ((JSONObject) -> Unit)? = null

    fun startControlReader() {
        Workers.runNamed("mc-ctl-$peerName") { controlLoop() }
    }

    private fun controlLoop() {
        try {
            val reader = RawLineReader(input)
            while (alive) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handleControl(line)
            }
        } catch (t: Throwable) {
            if (alive) Log.warn("Control reader ended: ${t.message}")
        } finally {
            if (alive) closeInternal(closedReason ?: "Connection lost")
        }
    }

    private fun handleControl(line: String) {
        val o = Wire.parse(line) ?: return
        when (o.optString("t")) {
            Wire.T_ACCEPT -> {
                peerAccepted = true
                acks["__hello"]?.complete(o)
            }
            Wire.T_REJECT -> {
                peerRejected = true
                acks["__hello"]?.complete(o)
            }
            Wire.T_ACK -> {
                val fileId = o.optString("fileId")
                acks[fileId]?.complete(o)
                acks["meta:$fileId"]?.complete(o)
            }
            Wire.T_DONE_OK -> {
                val fileId = o.optString("fileId")
                completions[fileId]?.complete(SendResult.Completed)
            }
            Wire.T_DONE_FAIL -> {
                val fileId = o.optString("fileId")
                val err = o.optString("err").ifBlank { "receiver rejected" }
                completions[fileId]?.complete(SendResult.Failed(err))
            }
            Wire.T_PAUSE -> {
                val fileId = o.optString("fileId")
                pausedFiles.add(fileId)
                completions[fileId]?.complete(SendResult.Paused)
                receiving[fileId]?.let { target ->
                    host.onItemState(this, target.item, ItemState.PAUSED, "Paused by sender")
                }
            }
            Wire.T_CANCEL -> {
                val fileId = o.optString("fileId")
                cancelledFiles.add(fileId)
                completions[fileId]?.complete(SendResult.Cancelled)
                receiving.remove(fileId)?.let { target ->
                    host.onItemState(this, target.item, ItemState.CANCELLED, null)
                }
            }
            Wire.T_META -> {
                try {
                    onMeta?.invoke(o)
                } catch (t: Throwable) {
                    Log.warn("META handling failed: ${t.message}")
                }
            }
            Wire.T_DONE -> {
                val fileId = o.optString("fileId")
                val ok = finishIncoming(fileId, o.optLong("bytes"), null)
                sendRaw(if (ok) Wire.doneOk(fileId) else Wire.doneFail(fileId, "verification failed"))
            }
            Wire.T_PROGRESS -> {
                // informational on the receiver's control plane during a send-back
            }
            Wire.T_BYE -> {
                closeInternal(o.optString("reason").ifBlank { "Session closed by peer" })
            }
            Wire.T_HELLO -> {
                // A second HELLO on an existing control line - acknowledge politely.
                sendRaw(Wire.accept(localDeviceId, localDeviceName))
            }
        }
    }

    @Volatile private var peerAccepted = false
    @Volatile private var peerRejected = false

    /** Sender side handshake; blocks (bounded) until the peer answers. */
    fun performHandshake(): Boolean {
        sendRaw(Wire.hello(localDeviceId, localDeviceName))
        val ack = Await<JSONObject>()
        acks["__hello"] = ack
        val res = ack.await(20_000)
        acks.remove("__hello")
        return res != null && res.optString("t") == Wire.T_ACCEPT
    }

    private fun sendRaw(line: String) {
        synchronized(writeLock) {
            try {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                out.flush()
            } catch (t: Throwable) {
                closeInternal("Connection lost")
            }
        }
    }

    fun send(line: String) = sendRaw(line)

    fun attachIncoming(target: ReceiveTarget) {
        receiving[target.item.id] = target
    }

    fun incomingCount(): Int = receiving.size

    // ---- sending -----------------------------------------------------------

    override fun sendFile(item: TransferItem, sha: String?): SendResult {
        if (!alive) return SendResult.Failed("Connection lost")
        cancelledFiles.remove(item.id)
        pausedFiles.remove(item.id)

        // 1. META -> ACK (receiver tells us its resume offset, or that it already has the file)
        val metaAck = Await<JSONObject>()
        acks["meta:${item.id}"] = metaAck
        sendRaw(Wire.meta(item.id, item.displayName, item.size, item.mime, sha, item.resumeOffset, item.batchId, item.groupId))
        val ack = metaAck.await(20_000)
        acks.remove("meta:${item.id}")
        if (ack == null) {
            return if (alive) SendResult.Failed("No response from peer") else SendResult.Failed("Connection lost")
        }
        when (ack.optString("status")) {
            "already" -> return SendResult.SkippedAlreadyPresent
            "reject" -> return SendResult.Failed(ack.optString("msg").ifBlank { "Rejected by peer" })
        }
        val offset = ack.optLong("offset", item.resumeOffset).coerceAtLeast(0L)
        var transferred = offset
        item.bytesTransferred = offset
        item.totalBytes = item.size
        item.state = ItemState.IN_PROGRESS
        host.onItemState(this, item, ItemState.IN_PROGRESS, null)

        val done = Await<SendResult>()
        completions[item.id] = done

        var dataSocket: Socket? = null
        try {
            dataSocket = Socket()
            dataSocket.tcpNoDelay = true
            dataSocket.soTimeout = Wire.READ_TIMEOUT_MS
            dataSocket.connect(java.net.InetSocketAddress(address, PORT), 12_000)
            val raw = BufferedOutputStream(dataSocket.getOutputStream(), 64 * 1024)
            raw.write(Wire.dataHeader(item.id, item.displayName, item.size, offset).toByteArray(Charsets.UTF_8))
            raw.write('\n'.code)
            raw.flush()

            val source = openSource(item.uri, offset)
            if (source == null) {
                completions.remove(item.id)
                return SendResult.Failed("Cannot open source file")
            }

            var seq = (offset / Wire.CHUNK).toInt()
            val buf = ByteArray(Wire.CHUNK)
            val frame = java.nio.ByteBuffer.allocate(12)
            var lastTick = System.currentTimeMillis()
            var bytesSinceTick = 0L
            source.use { ins ->
                while (true) {
                    if (cancelledFiles.contains(item.id)) {
                        completions.remove(item.id)
                        sendRaw(Wire.cancel(item.id))
                        return SendResult.Cancelled
                    }
                    if (pausedFiles.contains(item.id)) {
                        completions.remove(item.id)
                        item.resumeOffset = transferred
                        sendRaw(Wire.pause(item.id))
                        return SendResult.Paused
                    }
                    val n = ins.read(buf)
                    if (n <= 0) break
                    frame.clear()
                    frame.put(Wire.MAGIC.toByteArray(Charsets.US_ASCII))
                    frame.putInt(seq++)
                    frame.putInt(n)
                    raw.write(frame.array(), 0, 12)
                    raw.write(buf, 0, n)
                    val crc = Integrity.crc32(buf, n)
                    frame.clear()
                    frame.putInt(crc.toInt())
                    raw.write(frame.array(), 0, 4)
                    transferred += n
                    bytesSinceTick += n
                    item.bytesTransferred = transferred
                    item.resumeOffset = transferred
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 400) {
                        item.speedBps = bytesSinceTick * 1000 / (now - lastTick)
                        bytesSinceTick = 0
                        lastTick = now
                        host.onProgress(this, item)
                    }
                }
                raw.flush()
            }
            item.bytesTransferred = transferred
            sendRaw(Wire.progress(item.id, transferred))
            sendRaw(Wire.done(item.id, transferred, 0L))

            // Receiver verifies (CRC/chunk + sha when known) and answers DONE_OK / DONE_FAIL.
            val deadline = 60_000L + (item.size / 1024L) // generous but always bounded
            val result = done.await(deadline.coerceAtMost(30 * 60_000L))
            completions.remove(item.id)
            return result ?: if (alive) SendResult.Failed("Transfer did not complete") else SendResult.Failed("Connection lost")
        } catch (t: Throwable) {
            completions.remove(item.id)
            if (!alive) return SendResult.Failed("Connection lost")
            return SendResult.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            try { dataSocket?.close() } catch (ignored: Throwable) {}
        }
    }

    private fun openSource(uri: String, offset: Long): InputStream? = try {
        val parsed = Uri.parse(uri)
        val path = parsed.path
        val raw = if (parsed.scheme == "file" && path != null) {
            File(path).inputStream()
        } else {
            ctx.contentResolver.openInputStream(parsed)
        }
        if (raw == null) null
        else {
            var skipped = 0L
            while (skipped < offset) {
                val s = raw.skip(offset - skipped)
                if (s <= 0) break
                skipped += s
            }
            raw
        }
    } catch (t: Throwable) {
        null
    }

    override fun pauseOutgoing(fileId: String) {
        pausedFiles.add(fileId)
        sendRaw(Wire.pause(fileId))
        // INV-1(b): a 3 s terminal fallback force-completes the waiter if no update arrives.
        Workers.runNamed("mc-pause-fallback") {
            Thread.sleep(3000)
            completions[fileId]?.complete(SendResult.Paused)
        }
    }

    override fun cancelTransfer(fileId: String) {
        cancelledFiles.add(fileId)
        sendRaw(Wire.cancel(fileId))
        Workers.runNamed("mc-cancel-fallback") {
            Thread.sleep(3000)
            completions[fileId]?.complete(SendResult.Cancelled)
        }
    }

    // ---- receiving ---------------------------------------------------------

    /** Streams one incoming payload connection into its `.part` file. Runs on its own thread. */
    fun receiveData(socket: Link, header: JSONObject) {
        val fileId = header.optString("fileId")
        val target = receiving[fileId]
        if (target == null) {
            Log.warn("Payload connection for unknown file $fileId")
            try { socket.close() } catch (ignored: Throwable) {}
            return
        }
        try {
            val ins = socket.input
            val fileOut = java.io.FileOutputStream(target.partFile, true).buffered(64 * 1024)
            val header8 = ByteArray(12)
            val payload: ByteArray
            var written = 0L
            var lastTick = System.currentTimeMillis()
            var bytesSinceTick = 0L
            fileOut.use { fos ->
                while (true) {
                    if (!readFully(ins, header8, 12)) break
                    if (String(header8, 0, 4, Charsets.US_ASCII) != Wire.MAGIC) {
                        // Framing slipped - fail the file, never "repair" it by appending.
                        throw IllegalStateException("chunk magic mismatch - framing slipped")
                    }
                    val bb = java.nio.ByteBuffer.wrap(header8)
                    bb.position(4)
                    val seq = bb.int
                    val len = bb.int
                    if (len < 0 || len > Wire.MAX_LEN) throw IllegalStateException("invalid chunk length $len")
                    val data = ByteArray(len)
                    if (!readFully(ins, data, len)) throw IllegalStateException("short read")
                    val crcBuf = ByteArray(4)
                    if (!readFully(ins, crcBuf, 4)) throw IllegalStateException("missing crc")
                    val expected = java.nio.ByteBuffer.wrap(crcBuf).int
                    val actual = Integrity.crc32(data, len).toInt()
                    if (expected != actual) {
                        Log.warn("CRC mismatch on chunk seq=%04d - retrying".format(seq))
                        throw IllegalStateException("CRC mismatch on chunk seq=$seq")
                    }
                    fos.write(data, 0, len)
                    written += len
                    bytesSinceTick += len
                    target.item.bytesTransferred = target.resumeFrom + written
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 400) {
                        target.item.speedBps = bytesSinceTick * 1000 / (now - lastTick)
                        bytesSinceTick = 0
                        lastTick = now
                        host.onProgress(this, target.item)
                    }
                }
            }
            target.item.bytesTransferred = target.resumeFrom + written
            host.onProgress(this, target.item)
        } catch (t: Throwable) {
            target.failed = true
            target.error = t.message ?: "Transfer failed"
            Log.error("Data connection failed for ${target.item.displayName}: ${target.error}")
            // The sender is told the moment the data socket dies.
            val reason = target.error ?: "Transfer failed"
            sendRaw(Wire.doneFail(fileId, reason))
            host.onItemState(this, target.item, ItemState.FAILED, reason)
        } finally {
            try { socket.close() } catch (ignored: Throwable) {}
        }
    }

    private fun readFully(ins: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    /**
     * Called when the sender's DONE arrives: verify and atomically promote `.part` -> final.
     */
    fun finishIncoming(fileId: String, expectedBytes: Long, sha: String?): Boolean {
        val target = receiving.remove(fileId) ?: return false
        if (target.failed) return false
        return try {
            val size = target.partFile.length()
            if (expectedBytes > 0 && size < expectedBytes) {
                // Sender said more bytes than we hold - keep the .part for resume.
                target.item.resumeOffset = size
                host.onItemState(this, target.item, ItemState.PAUSED, "Incomplete - resume available")
                return false
            }
            if (sha != null && sha.isNotBlank()) {
                val actual = Integrity.sha256File(target.partFile)
                if (actual != null && !actual.equals(sha, ignoreCase = true)) {
                    target.item.lastError = "Checksum mismatch"
                    host.onItemState(this, target.item, ItemState.FAILED, "Checksum mismatch")
                    return false
                }
            }
            val ok = Destinations.promote(target.partFile, target.finalFile)
            if (!ok) {
                host.onItemState(this, target.item, ItemState.FAILED, "Could not save file")
                return false
            }
            target.item.bytesTransferred = size
            target.item.totalBytes = if (expectedBytes > 0) expectedBytes else size
            host.onItemState(this, target.item, ItemState.COMPLETED, null)
            true
        } catch (t: Throwable) {
            host.onItemState(this, target.item, ItemState.FAILED, t.message)
            false
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun close(reason: String) {
        try {
            sendRaw(Wire.bye(reason))
        } catch (ignored: Throwable) {
        }
        closeInternal(reason)
    }

    /** Called silently from a peer's BYE - no BYE is echoed back. */
    fun closeFromPeer(reason: String) = closeInternal(reason)

    private fun closeInternal(reason: String) {
        val wasAlive = alive
        alive = false
        closedReason = reason
        // INV-1(a) + INV-3: fail every pending send waiter immediately, then clear maps.
        for ((_, box) in completions) box.complete(SendResult.Failed(reason))
        for ((_, box) in acks) box.complete(JSONObject().put("t", Wire.T_REJECT).put("reason", reason))
        completions.clear()
        acks.clear()
        pausedFiles.clear()
        try { link.close() } catch (ignored: Throwable) {}
        if (wasAlive) host.onSessionClosed(this, reason)
    }

    companion object {
        const val PORT = com.morsecode.app.BuildConfig.TCP_PORT

        /** Receiver-side payload links (a data connection per file) are handled by the transport. */
        const val KIND = com.morsecode.app.core.model.TransportKind.LAN
    }
}

/** LAN sessions are just stream sessions over TCP. */
typealias LanSession = StreamSession

/**
 * Reads a single line byte-by-byte straight from the raw stream.
 *
 * CRITICAL FRAMING RULE: a data connection's first line must be read this way. Wrapping the
 * socket in a BufferedReader before the JSON header is consumed lets its 8 KB pre-read swallow
 * the first MLNK frame - the classic "chunk magic mismatch - framing slipped" regression.
 */
class RawLineReader(private val ins: InputStream) {

    fun readLine(limit: Int = 8192): String? {
        val buf = java.io.ByteArrayOutputStream(256)
        var count = 0
        while (true) {
            val b = ins.read()
            if (b < 0) return if (count == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) return String(buf.toByteArray(), Charsets.UTF_8)
            if (b != '\r'.code) buf.write(b)
            if (++count > limit) return null
        }
    }
}
