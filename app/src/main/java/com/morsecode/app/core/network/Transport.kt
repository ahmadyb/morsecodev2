package com.morsecode.app.core.network

import com.morsecode.app.core.model.DiscoveredPeer
import com.morsecode.app.core.model.ItemState
import com.morsecode.app.core.model.SendResult
import com.morsecode.app.core.model.TransferItem

/**
 * A connected peer. Two implementations ship: [LanSession] (TCP) and [NearbySession]
 * (Google Nearby Connections). Both must honour INV-1/INV-2/INV-3 of the engine spec:
 *
 *  - INV-1 a worker never waits forever: close() completes every pending waiter,
 *    pause/cancel schedule a 3 s terminal fallback, and every await is bounded.
 *  - INV-2 pausing or cancelling one file affects only that item, for that peer.
 *  - INV-3 when a session is lost every in-progress/queued send becomes PAUSED
 *    ("Connection lost") and is re-queued when the peer comes back.
 */
interface TransportSession {

    val peerId: String
    val peerName: String
    val transport: String
    val address: String

    /** Device descriptor shown in the peer chip, e.g. "192.168.1.42 · LAN". */
    val peerDetail: String

    val isAlive: Boolean

    /** Sends one file and blocks the calling worker until a terminal result exists. */
    fun sendFile(item: TransferItem, sha: String?): SendResult

    fun pauseOutgoing(fileId: String)
    fun cancelTransfer(fileId: String)
    fun close(reason: String)
}

/** Callbacks the engine hands to a transport/session. */
interface SessionHost {
    /** A peer asked to connect; returns true when the user accepted. */
    fun requestConsent(peer: DiscoveredPeer, session: TransportSession): Boolean

    /** A session became usable (both directions may send now). */
    fun onSessionEstablished(session: TransportSession)

    /** A peer announced an incoming file (receiver side). */
    fun onIncomingFile(session: TransportSession, item: TransferItem)

    /** Bytes moved for an item (both directions). */
    fun onProgress(session: TransportSession, item: TransferItem)

    /** Item reached a new state. */
    fun onItemState(session: TransportSession, item: TransferItem, state: ItemState, error: String?)

    /** Session finished for good. */
    fun onSessionClosed(session: TransportSession, reason: String)

    /** Log line owner. */
    fun log(level: Int, message: String)
}

/** A discovery + connection engine for one transport technology. */
interface Transport {

    val kind: String

    fun start()
    fun stop()

    /**
     * Whether [start] actually brought this transport up. A transport refuses to start for
     * ordinary reasons (radio off, permission missing, no adapter), and the caller has to be
     * able to tell "started" apart from "asked to start" - otherwise the UI shows a search
     * that is not happening.
     */
    val isRunning: Boolean

    fun connect(peer: DiscoveredPeer, host: SessionHost): TransportSession?

    fun beacon(): String
}
