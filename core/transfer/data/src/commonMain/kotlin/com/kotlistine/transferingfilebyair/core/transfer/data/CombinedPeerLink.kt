package com.kotlistine.transferingfilebyair.core.transfer.data

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Two links behind one: the phones' own ([primary]: Nearby on Android) and a second one for other devices
 * ([secondary]: the local network, for a laptop). The screens see a single link.
 *
 * While [secondary] isn't linked, everything is [primary]'s, exactly as if it were alone: its state (unless
 * [secondary] has something to show), its messages, its media, its files. Once one of them links up, the other stops
 * looking, so the device is never linked to two at once; when that link ends, both look again.
 */
class CombinedPeerLink(
    private val primary: PeerLink,
    private val secondary: PeerLink,
    private val scope: CoroutineScope,
) : PeerLink {

    /** Of two devices found at once, the one found last is offered. */
    private var foundLast: PeerLink? = null

    /** The link the user last asked to pair through: its failure is the one to show. */
    private var askedLast: PeerLink? = null

    private var wantsReconnect = false

    private val _state = MutableStateFlow(merged())
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Whether [secondary] is the live link now: what the line-up and the files follow. */
    val usesSecondary: StateFlow<Boolean> = state
        .map { isSecondaryLinked() }
        .stateIn(scope, SharingStarted.Eagerly, isSecondaryLinked())

    init {
        listOf(primary, secondary).forEach { link ->
            link.state.onEach { linkState ->
                if (linkState is LinkState.Found) foundLast = link
                onLinkState(link, linkState)
                _state.value = merged()
            }.launchIn()
        }
    }

    override val transport: Transport = object : Transport {
        override val incoming: Flow<SlideMessage> = merge(primary.transport.incoming, secondary.transport.incoming)

        override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> = live().transport.send(message)
    }

    override val media: MediaChannel? get() = live().media

    override suspend fun startSearching(): EmptyResult<DataError.Network> {
        if (isLinked()) return Result.Success(Unit)
        val first = primary.startSearching()
        val second = secondary.startSearching()
        // Finding one kind of device is enough; both failing says why the phones can't be found.
        return if (first is Result.Failure && second is Result.Failure) first else Result.Success(Unit)
    }

    override fun stopSearching() {
        primary.stopSearching()
        secondary.stopSearching()
    }

    override suspend fun reconnect() {
        wantsReconnect = true
        when {
            primary.state.value is LinkState.Connected -> primary.reconnect()
            isSecondaryLinked() -> secondary.reconnect()
            else -> {
                primary.reconnect()
                secondary.reconnect()
            }
        }
    }

    override fun pauseReconnecting() {
        wantsReconnect = false
        primary.pauseReconnecting()
        secondary.pauseReconnecting()
    }

    override suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network> {
        val link = owner(peer) ?: return Result.Failure(DataError.Network.DISCONNECTED)
        askedLast = link
        other(link).stopSearching()
        return link.connect(peer)
    }

    override suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network> {
        val link = owner(peer) ?: return Result.Failure(DataError.Network.DISCONNECTED)
        askedLast = link
        return link.accept(peer)
    }

    override fun decline(peer: PeerDevice) {
        owner(peer)?.decline(peer)
    }

    override fun disconnect() = live().disconnect()

    override suspend fun sendFile(
        uri: String,
        announce: suspend (transferId: Long) -> Unit,
        onProgress: (bytesSent: Long) -> Unit,
    ): EmptyResult<DataError.Network> = live().sendFile(uri, announce, onProgress)

    override suspend fun receiveFile(
        transferId: Long,
        onProgress: (bytesReceived: Long) -> Unit,
    ): Result<String, DataError.Network> = live().receiveFile(transferId, onProgress)

    override fun cancelTransfer(transferId: Long) = live().cancelTransfer(transferId)

    /** [secondary] while it alone is linked; [primary] otherwise, as if it were alone. */
    private fun live(): PeerLink = if (isSecondaryLinked()) secondary else primary

    private fun isSecondaryLinked() =
        secondary.state.value is LinkState.Connected && primary.state.value !is LinkState.Connected

    private fun isLinked() = primary.state.value is LinkState.Connected || secondary.state.value is LinkState.Connected

    private fun other(link: PeerLink) = if (link === primary) secondary else primary

    /** The link that reported [peer]: found it, is pairing with it, or is linked to it. */
    private fun owner(peer: PeerDevice): PeerLink? =
        listOf(primary, secondary).firstOrNull { it.state.value.peer?.id == peer.id }

    /** One link came up: the other stops looking. One went down: both look again, if the app wants that. */
    private fun onLinkState(link: PeerLink, linkState: LinkState) {
        val other = other(link)
        if (linkState is LinkState.Connected) {
            other.pauseReconnecting()
            other.stopSearching()
        } else if (linkState is LinkState.Idle && wantsReconnect && other.state.value is LinkState.Idle) {
            scope.launch { other.reconnect() }
        }
    }

    private fun merged(): LinkState {
        val a = primary.state.value
        val b = secondary.state.value
        return when {
            a is LinkState.Connected -> a
            b is LinkState.Connected -> b
            a.isPairing -> a
            b.isPairing -> b
            askedLast === secondary && b is LinkState.Failed -> b
            a is LinkState.Found && b is LinkState.Found -> if (foundLast === secondary) b else a
            a is LinkState.Found -> a
            b is LinkState.Found -> b
            a is LinkState.Searching || b is LinkState.Searching -> LinkState.Searching
            a is LinkState.Failed -> a
            b is LinkState.Failed -> b
            else -> a
        }
    }

    private fun Flow<*>.launchIn() = scope.launch { collect { } }

    private val LinkState.isPairing: Boolean get() = this is LinkState.Incoming || this is LinkState.Connecting

    private val LinkState.peer: PeerDevice?
        get() = when (this) {
            is LinkState.Found -> peer
            is LinkState.Incoming -> peer
            is LinkState.Connecting -> peer
            is LinkState.Connected -> peer
            else -> null
        }
}

/** The line-up of whichever link is live: each device next door sits in its own place. */
class CombinedLinkGeometry(
    private val primary: LinkGeometry,
    private val secondary: LinkGeometry,
    private val usesSecondary: StateFlow<Boolean>,
    scope: CoroutineScope,
) : LinkGeometry {
    override val link: StateFlow<SlideLink> = combine(usesSecondary, primary.link, secondary.link) { second, a, b ->
        if (second) b else a
    }.stateIn(scope, SharingStarted.Eagerly, if (usesSecondary.value) secondary.link.value else primary.link.value)

    override fun update(link: SlideLink) = (if (usesSecondary.value) secondary else primary).update(link)
}
