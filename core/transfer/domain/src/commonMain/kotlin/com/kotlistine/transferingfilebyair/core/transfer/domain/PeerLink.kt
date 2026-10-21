package com.kotlistine.transferingfilebyair.core.transfer.domain

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import kotlinx.coroutines.flow.StateFlow

/** A nearby device running the app. [id] is only meaningful to the link that found it. */
data class PeerDevice(val id: String, val name: String)

/** Where the link to the device next door stands. */
sealed interface LinkState {
    data object Idle : LinkState

    /** Visible to others and looking for them; nobody found yet. */
    data object Searching : LinkState

    /** A device nearby is ready to pair. */
    data class Found(val peer: PeerDevice) : LinkState

    /**
     * A pairing attempt with [peer] (whichever phone asked) waits for this device's user to confirm. [code] is shown
     * on both phones so they can check it is the phone in front of them; nothing connects until each user accepts.
     */
    data class Incoming(val peer: PeerDevice, val code: String) : LinkState

    /** Waiting for the link; [code] (once both sides know it) matches the one on the other phone. */
    data class Connecting(val peer: PeerDevice, val code: String? = null) : LinkState

    data class Connected(val peer: PeerDevice) : LinkState

    data class Failed(val error: DataError.Network) : LinkState
}

/**
 * Finding, pairing with and talking to the device next to this one (Nearby Connections on Android). Both devices
 * search at once. One user asks ([connect]); then both phones show who they are pairing with and the same code, and
 * each pairs only when its own user [accept]s, like Bluetooth pairing. Nothing pairs without a person checking the
 * code on each phone.
 */
interface PeerLink {
    val state: StateFlow<LinkState>

    /** Messages to and from the connected peer. Sends fail with [DataError.Network.DISCONNECTED] while unpaired. */
    val transport: Transport

    /** Makes this device visible and looks for others, until it connects or [stopSearching] is called. */
    suspend fun startSearching(): EmptyResult<DataError.Network>

    fun stopSearching()

    /**
     * While the app is open: if this phone paired before, look for that phone and link up again by itself whenever
     * it comes into reach (no code to check: it proves it is the same phone). Quietly does nothing if it can't look.
     */
    suspend fun reconnect()

    /** The app went to the background: stop looking for the remembered phone (a live link stays up). */
    fun pauseReconnecting()

    /** Asks [peer] to pair; it pairs once the peer's user accepts. */
    suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network>

    /** Agrees to the pending [LinkState.Incoming] request from [peer]. */
    suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network>

    /** Turns down the pending [LinkState.Incoming] request from [peer]. */
    fun decline(peer: PeerDevice)

    /** Unpairs: both phones forget each other and won't link up again by themselves. */
    fun disconnect()

    /**
     * Streams the file at [uri] (a platform file reference) to the connected peer. [announce] runs once the transfer
     * has its id, before any bytes go; this returns once the peer has the whole file, or fails.
     */
    suspend fun sendFile(uri: String, announce: suspend (transferId: Long) -> Unit): EmptyResult<DataError.Network>

    /**
     * Waits for the file the peer sends as [transferId] to arrive in full, reporting how much has arrived (0..1) to
     * [onProgress] as it comes in. Returns where it was stored locally.
     */
    suspend fun receiveFile(transferId: Long, onProgress: (Float) -> Unit = {}): Result<String, DataError.Network>

    /** Stops transfer [transferId] in either direction; both sides then fail it with [DataError.Network.CANCELLED]. */
    fun cancelTransfer(transferId: Long)
}
