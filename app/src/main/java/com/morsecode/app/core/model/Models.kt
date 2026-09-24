package com.morsecode.app.core.model

import java.util.UUID

/** Direction of a single queued unit. */
enum class Direction { SENDING, RECEIVING }

/**
 * QUEUED -> IN_PROGRESS -> COMPLETED | SKIPPED | PAUSED | CANCELLED | FAILED
 */
enum class ItemState { QUEUED, IN_PROGRESS, COMPLETED, SKIPPED, PAUSED, CANCELLED, FAILED }

/** What a session hands back when a send finishes. */
sealed class SendResult {
    object Completed : SendResult()
    object SkippedAlreadyPresent : SendResult()
    object Paused : SendResult()
    object Cancelled : SendResult()
    data class Failed(val error: String) : SendResult()
}

/** A file the user (or a peer) wants moved. */
class TransferItem(
    val id: String = UUID.randomUUID().toString(),
    val batchId: String,
    val groupId: String? = null,
    var peerId: String,
    val peerName: String,
    val direction: Direction,
    val displayName: String,
    val uri: String,
    val mime: String,
    val size: Long,
    var totalBytes: Long = size,
    var bytesTransferred: Long = 0L,
    var speedBps: Long = 0L,
    var resumeOffset: Long = 0L,
    var retryCount: Int = 0,
    var lastError: String? = null,
    var sha256: String? = null,
    var state: ItemState = ItemState.QUEUED,
    /** true while a peer beyond DeviceTier.maxParallelPeers waits for a slot in a Broadcast */
    var waitingForSlot: Boolean = false,
    var startedAt: Long = 0L,
    var finishedAt: Long = 0L
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f else (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val isTerminal: Boolean
        get() = state == ItemState.COMPLETED || state == ItemState.SKIPPED ||
            state == ItemState.CANCELLED || state == ItemState.FAILED
}

/** One Broadcast = one group. */
class TransferGroup(
    val groupId: String = UUID.randomUUID().toString(),
    val batchId: String = UUID.randomUUID().toString(),
    var peerIds: MutableList<String> = mutableListOf(),
    var peerNames: MutableMap<String, String> = mutableMapOf(),
    var startedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long = 0L,
    var completed: Boolean = false
)

/** A peer the transports can see (or that history remembers). */
class DiscoveredPeer(
    val deviceId: String,
    var name: String,
    var transport: String = TransportKind.LAN,
    var address: String = "",
    var port: Int = 33456,
    var rssi: Int = 0,
    var lastSeen: Long = System.currentTimeMillis(),
    var signal: Int = 3,
    /** null = unknown yet, true/false = PeerConnectionState */
    @Volatile var connected: Boolean = false,
    @Volatile var busy: Boolean = false
) {
    val initial: String get() = if (name.isBlank()) "?" else name.trim().substring(0, 1).uppercase()
}

object TransportKind {
    const val LAN = "lan"
    const val NEARBY = "nearby"
    const val WEB = "web"

    fun label(kind: String): String = when (kind) {
        NEARBY -> "Nearby"
        WEB -> "WebShare"
        else -> "Wi-Fi LAN"
    }
}

/** One finished (or failed) transfer, as shown in History. */
class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val peerName: String,
    val direction: String,           // "sent" | "received"
    val size: Long,
    val ok: Boolean,
    val whenMs: Long = System.currentTimeMillis(),
    val error: String? = null,
    val transport: String = TransportKind.LAN,
    val uri: String = ""
)

/** A Broadcast is recorded as exactly one of these - never one row per peer. */
class GroupHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val direction: String,
    val whenMs: Long = System.currentTimeMillis(),
    val peerOutcomes: MutableList<PeerOutcome> = mutableListOf(),
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
    val label: String = ""
) {
    val phoneCount: Int get() = peerOutcomes.size
    val allOk: Boolean get() = peerOutcomes.all { it.failed == 0 && it.skipped == 0 }
}

class PeerOutcome(
    val peerId: String,
    val peerName: String,
    val transport: String,
    var sent: Int = 0,
    var failed: Int = 0,
    var skipped: Int = 0,
    var avgSpeedBps: Long = 0L,
    var outcome: String = "completed"
) {
    val ok: Boolean get() = failed == 0 && outcome == "completed"
}
