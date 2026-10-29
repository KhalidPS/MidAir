package com.kotlistine.transferingfilebyair.core.transfer.nearby

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.data.SlideMessageCodec
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Only installs of this app find each other. */
private const val ServiceId = "com.kotlistine.transferingfilebyair.slide"

/** A message taking longer than this to go out is logged. */
private const val SlowSendMillis = 300L

/** How long a link that came up by itself has to prove it is the remembered phone. */
private const val ProofTimeoutMillis = 8_000L

/**
 * [PeerLink] on Nearby Connections: Bluetooth to find the other phone and connect, then an automatic upgrade to
 * Wi-Fi Direct or a hotspot for bandwidth. No shared Wi-Fi network is needed. Point-to-point, since a slide goes to
 * exactly one device. Nearby calls back on the main thread, so all state here changes on it.
 *
 * Once both users have confirmed a pairing, the two phones agree on a key ([LinkFrame.Trust]) and remember each other
 * ([memory]). In later sessions they look for each other while the app is open and link up without asking, but a
 * link that came up that way only counts once the other end proves it holds the key ([LinkFrame.Proof]); until then
 * nothing it sends gets through.
 */
internal class NearbyPeerLink(context: Context, private val memory: PeerMemory) : PeerLink {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = Nearby.getConnectionsClient(context)
    private val localName = deviceName(context)
    private val strategy = Strategy.P2P_POINT_TO_POINT

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Devices currently in range, by endpoint id, newest last. */
    private val nearby = LinkedHashMap<String, PeerDevice>()
    private var connectedEndpoint: String? = null

    /** The endpoint this device asked to pair with (its user held Connect), until the attempt ends. */
    private var requestedEndpoint: String? = null

    /** The remembered phone, linking up by itself: accepted without asking, then it must prove who it is. */
    private var rememberedEndpoint: String? = null

    /** The live link's introductions (see [LinkFrame]). */
    private var handshake: Handshake? = null

    /** The app is open and wants the remembered phone found again whenever it is out of reach. */
    private var wantsReconnect = false
    private val received = MutableSharedFlow<SlideMessage>(extraBufferCapacity = 256)

    /**
     * File transfers by payload id, completed with their outcome (for incoming files, where the bytes landed). Kept
     * after completing, so a file that finished before anyone asked for it is still there to collect.
     */
    private val transfers = ConcurrentHashMap<Long, CompletableDeferred<Result<String?, DataError.Network>>>()
    private val incomingFiles = ConcurrentHashMap<Long, Payload>()

    /** Who wants to hear how far an incoming file has got, by transfer id. */
    private val progressListeners = ConcurrentHashMap<Long, (Float) -> Unit>()

    private fun transfer(id: Long) = transfers.getOrPut(id) { CompletableDeferred() }

    /** Only once the link counts: a remembered phone that hasn't proven itself reads nothing. */
    override val media = NearbyMediaChannel(context, client, scope) {
        connectedEndpoint?.takeIf { _state.value is LinkState.Connected }
    }

    override val transport: Transport = object : Transport {
        override val incoming: Flow<SlideMessage> = received

        override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
            val endpoint = connectedEndpoint ?: return Result.Failure(DataError.Network.DISCONNECTED)
            val bytes = when (val encoded = SlideMessageCodec.encode(message)) {
                is Result.Failure -> return encoded
                is Result.Success -> encoded.data
            }
            val startedAt = System.currentTimeMillis()
            val sent = nearbyCall { client.sendPayload(endpoint, Payload.fromBytes(bytes)).await() }
            val millis = System.currentTimeMillis() - startedAt
            // Messages go out one at a time: a slow one holds up everything after it.
            if (millis > SlowSendMillis) Log.w("AirLink", "Sending ${message::class.simpleName} took $millis ms")
            return sent
        }
    }

    override suspend fun startSearching(): EmptyResult<DataError.Network> = search()

    override suspend fun reconnect() {
        wantsReconnect = true
        if (memory.peer == null || _state.value !is LinkState.Idle) return
        // Quietly: a phone that can't search right now (Location off, no permission) just doesn't reconnect.
        if (search() is Result.Failure) {
            client.stopAdvertising()
            client.stopDiscovery()
            _state.value = LinkState.Idle
        }
    }

    override fun pauseReconnecting() {
        wantsReconnect = false
        if (_state.value is LinkState.Searching || _state.value is LinkState.Found) stopSearching()
    }

    private suspend fun search(): EmptyResult<DataError.Network> {
        // Paired, or a pairing attempt is under way (e.g. the screen just resumed): leave it be.
        val current = _state.value
        if (current is LinkState.Connected || current is LinkState.Incoming || current is LinkState.Connecting) {
            return Result.Success(Unit)
        }
        _state.value = nearby.values.lastOrNull()?.let(LinkState::Found) ?: LinkState.Searching
        val advertising = nearbyCall {
            client.startAdvertising(localName, ServiceId, connections, AdvertisingOptions.Builder().setStrategy(strategy).build()).await()
        }
        if (advertising is Result.Failure) return fail(advertising.error)
        val discovering = nearbyCall {
            client.startDiscovery(ServiceId, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build()).await()
        }
        if (discovering is Result.Failure) {
            client.stopAdvertising()
            return fail(discovering.error)
        }
        return Result.Success(Unit)
    }

    /** Stops being visible and withdraws any pairing attempt still waiting on someone; a live link stays up. */
    override fun stopSearching() {
        client.stopAdvertising()
        client.stopDiscovery()
        nearby.clear()
        if (_state.value is LinkState.Connected) return
        pendingEndpoint(requestedEndpoint, _state.value)?.let { endpoint ->
            client.rejectConnection(endpoint)
            client.disconnectFromEndpoint(endpoint)
        }
        requestedEndpoint = null
        rememberedEndpoint = null
        _state.value = LinkState.Idle
        // Leaving the pairing screen unpaired: keep looking for the remembered phone, as before it opened.
        if (wantsReconnect) scope.launch { reconnect() }
    }

    override suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network> {
        requestedEndpoint = peer.id
        _state.value = LinkState.Connecting(peer)
        // Success here only means the request went out; the outcome arrives in onConnectionResult.
        val requested = nearbyCall { client.requestConnection(localName, peer.id, connections).await() }
        if (requested is Result.Failure) {
            // Cancelled while the request was going out: that failure is the cancel, not an error to show.
            if (!isStillConnecting(peer)) return Result.Success(Unit)
            requestedEndpoint = null
            return fail(requested.error)
        }
        return requested
    }

    override suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network> {
        val incoming = _state.value as? LinkState.Incoming
        if (incoming == null || incoming.peer.id != peer.id) return Result.Failure(DataError.Network.DISCONNECTED)
        _state.value = LinkState.Connecting(peer, incoming.code)
        val accepted = nearbyCall { client.acceptConnection(peer.id, payloads).await() }
        if (accepted is Result.Failure) {
            if (!isStillConnecting(peer)) return Result.Success(Unit)
            return fail(accepted.error)
        }
        return accepted
    }

    /** Whether the attempt with [peer] is still the one under way (not cancelled or replaced meanwhile). */
    private fun isStillConnecting(peer: PeerDevice): Boolean =
        (_state.value as? LinkState.Connecting)?.peer?.id == peer.id

    override fun decline(peer: PeerDevice) {
        val incoming = _state.value as? LinkState.Incoming ?: return
        if (incoming.peer.id != peer.id) return
        client.rejectConnection(peer.id)
        _state.value = afterAttempt()
    }

    /** Back to whatever is in range once a pairing attempt is over. */
    private fun afterAttempt(): LinkState = nearby.values.lastOrNull()?.let(LinkState::Found) ?: LinkState.Searching

    /** Unpairs: the other phone is told to forget this one too, so neither links up again by itself. */
    override fun disconnect() {
        connectedEndpoint?.let { endpoint ->
            client.sendPayload(endpoint, Payload.fromBytes(LinkFrame.Forget.encode()))
                .addOnCompleteListener { client.disconnectFromEndpoint(endpoint) }
        }
        memory.forget()
        dropLink()
    }

    /** The live link is gone (or never counted): every transfer on it fails, and the remembered phone is looked for. */
    private fun dropLink() {
        connectedEndpoint = null
        handshake?.timeout?.cancel()
        handshake = null
        abandonTransfers()
        media.onLinkDropped()
        _state.value = LinkState.Idle
        if (wantsReconnect) scope.launch { reconnect() }
    }

    /** Ends a link that didn't prove it is the remembered phone. */
    private fun refuse(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        if (connectedEndpoint == endpointId) dropLink()
    }

    private fun sendFrame(endpointId: String, frame: LinkFrame) {
        client.sendPayload(endpointId, Payload.fromBytes(frame.encode()))
    }

    /** The two ends introduce themselves, then agree on a key (after a confirmed pairing) or prove they hold it. */
    private fun onFrame(endpointId: String, frame: LinkFrame) {
        val shake = handshake?.takeIf { it.endpointId == endpointId } ?: return
        when (frame) {
            is LinkFrame.Hello -> {
                shake.peerId = frame.deviceId
                val trusted = memory.peer?.takeIf { it.id.contentEquals(frame.deviceId) }
                // Whenever this end remembers the other, it proves it: the other end may have linked up by itself
                // (and does, even when this end's user held Connect).
                if (trusted != null) sendFrame(endpointId, LinkFrame.Proof(prove(trusted.key, frame.nonce, memory.ownId)))
                when {
                    // After a pairing both users confirmed, a fresh key for next time (sent after the proof).
                    shake.isConfirmed -> if (handsOutKey(memory.ownId, frame.deviceId)) {
                        val key = memory.random(KeyBytes)
                        memory.trust(TrustedPeer(frame.deviceId, shake.peer.name, key))
                        sendFrame(endpointId, LinkFrame.Trust(key))
                    }
                    trusted == null -> refuse(endpointId)
                }
            }
            is LinkFrame.Proof -> {
                if (shake.isConfirmed || shake.isProven) return
                val trusted = memory.peer
                val peerId = shake.peerId
                val expected = if (trusted != null && peerId != null) prove(trusted.key, shake.nonce, peerId) else null
                if (expected == null || !MessageDigest.isEqual(expected, frame.mac)) return refuse(endpointId)
                shake.isProven = true
                shake.timeout?.cancel()
                _state.value = LinkState.Connected(shake.peer)
            }
            is LinkFrame.Trust -> {
                val peerId = shake.peerId ?: return
                // Only from an end both users confirmed, or that has proven itself; and only from the end that hands
                // out keys.
                if (!(shake.isConfirmed || shake.isProven) || handsOutKey(memory.ownId, peerId)) return
                memory.trust(TrustedPeer(peerId, shake.peer.name, frame.key))
            }
            LinkFrame.Forget -> memory.forget()
            // The media channel's; routed there before this.
            is LinkFrame.RangeRequest, is LinkFrame.RangeOpened, is LinkFrame.RangeRefused,
            is LinkFrame.DigestRequest, is LinkFrame.Digest,
            -> Unit
        }
    }

    override suspend fun sendFile(
        uri: String,
        announce: suspend (transferId: Long) -> Unit,
    ): EmptyResult<DataError.Network> {
        val endpoint = connectedEndpoint ?: return Result.Failure(DataError.Network.DISCONNECTED)
        val descriptor = try {
            withContext(Dispatchers.IO) { appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r") }
        } catch (_: Exception) {
            null
        } ?: return Result.Failure(DataError.Network.UNKNOWN)
        return descriptor.use {
            val payload = Payload.fromFile(descriptor)
            val outcome = transfer(payload.id)
            try {
                announce(payload.id)
                val sent = nearbyCall { client.sendPayload(endpoint, payload).await() }
                if (sent is Result.Failure) return@use sent
                when (val result = outcome.await()) {
                    is Result.Success -> Result.Success(Unit)
                    is Result.Failure -> result
                }
            } finally {
                transfers.remove(payload.id)
            }
        }
    }

    override suspend fun receiveFile(transferId: Long, onProgress: (Float) -> Unit): Result<String, DataError.Network> {
        progressListeners[transferId] = onProgress
        val result = try {
            transfer(transferId).await()
        } finally {
            progressListeners.remove(transferId)
        }
        transfers.remove(transferId)
        return when (result) {
            is Result.Failure -> result
            is Result.Success -> result.data?.let { Result.Success(it) } ?: Result.Failure(DataError.Network.UNKNOWN)
        }
    }

    /** When the last message of a card in the air came in; null when none is in the air. */
    private var lastSlideMessageAt: Long? = null

    /**
     * Logs a long pause in the stream of a card's moves (and its video's clock), which come many times a second while
     * it is in the air: something else on the link held them up.
     */
    private fun noteGap(message: SlideMessage) {
        val now = System.currentTimeMillis()
        val last = lastSlideMessageAt
        if (last != null && now - last > SlowSendMillis) {
            Log.w("AirLink", "Card messages paused ${now - last} ms, then ${message::class.simpleName}")
        }
        lastSlideMessageAt = when (message) {
            is SlideMessage.DragMove, is SlideMessage.PlaybackClock, is SlideMessage.DragStart,
            is SlideMessage.PlaybackOffer,
            -> now
            else -> null
        }
    }

    override fun cancelTransfer(transferId: Long) {
        client.cancelPayload(transferId)
        // Settle it here too, in case the link reports nothing more for it.
        transfer(transferId).complete(Result.Failure(DataError.Network.CANCELLED))
        incomingFiles.remove(transferId)
    }

    /** The link is gone: every transfer still under way has failed. Finished ones keep their result. */
    private fun abandonTransfers() {
        transfers.values.forEach { it.complete(Result.Failure(DataError.Network.DISCONNECTED)) }
        incomingFiles.clear()
    }

    /** A phone in range with the remembered phone's name (it still has to prove it is that phone). */
    private fun isRemembered(name: String): Boolean = memory.peer?.name == name

    /** Not linked, and no pairing attempt under way. */
    private fun isFree(): Boolean {
        val current = _state.value
        return connectedEndpoint == null && requestedEndpoint == null && rememberedEndpoint == null &&
            current !is LinkState.Incoming && current !is LinkState.Connecting && current !is LinkState.Connected
    }

    /** Asks the remembered phone to link up; it accepts without asking its user (see onConnectionInitiated). */
    private fun linkUpWith(peer: PeerDevice) {
        requestedEndpoint = peer.id
        rememberedEndpoint = peer.id
        _state.value = LinkState.Connecting(peer)
        scope.launch {
            val requested = nearbyCall { client.requestConnection(localName, peer.id, connections).await() }
            if (requested is Result.Failure && rememberedEndpoint == peer.id) giveUpLinkingUp()
        }
    }

    private fun giveUpLinkingUp() {
        requestedEndpoint = null
        rememberedEndpoint = null
        _state.value = afterAttempt()
    }

    private fun fail(error: DataError.Network): EmptyResult<DataError.Network> {
        _state.value = LinkState.Failed(error)
        return Result.Failure(error)
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != ServiceId) return
            val peer = PeerDevice(endpointId, info.endpointName)
            nearby[endpointId] = peer
            if (isRemembered(info.endpointName) && asksToReconnect(memory.ownId, memory.peer!!.id) && isFree()) {
                linkUpWith(peer)
                return
            }
            if (_state.value is LinkState.Searching) _state.value = LinkState.Found(peer)
        }

        override fun onEndpointLost(endpointId: String) {
            nearby.remove(endpointId)
            val current = _state.value
            if (current is LinkState.Found && current.peer.id == endpointId) {
                _state.value = nearby.values.lastOrNull()?.let(LinkState::Found) ?: LinkState.Searching
            }
        }
    }

    private val connections = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peer = PeerDevice(endpointId, info.endpointName)
            val code = info.authenticationDigits
            val asksBack = info.isIncomingConnection && isRemembered(info.endpointName) && isFree()
            if (endpointId == rememberedEndpoint || asksBack) {
                // The remembered phone: no need to ask, it proves who it is once linked.
                rememberedEndpoint = endpointId
                _state.value = LinkState.Connecting(peer)
                scope.launch {
                    val accepted = nearbyCall { client.acceptConnection(endpointId, payloads).await() }
                    if (accepted is Result.Failure && rememberedEndpoint == endpointId) giveUpLinkingUp()
                }
                return
            }
            val decision = decideRequest(
                isIncoming = info.isIncomingConnection,
                endpointId = endpointId,
                requestedEndpoint = requestedEndpoint,
                connectedEndpoint = connectedEndpoint,
                state = _state.value,
            )
            when (decision) {
                // On both phones, nothing connects until its user confirms, after checking the code.
                RequestDecision.ASK_USER -> _state.value = LinkState.Incoming(peer, code)
                RequestDecision.REJECT -> client.rejectConnection(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val current = _state.value
            // Results for requests this device turned down (or withdrew) are none of the screen's business.
            if (!isCurrentAttempt(endpointId, requestedEndpoint, current)) return
            if (endpointId == requestedEndpoint) requestedEndpoint = null
            val pending = (current as? LinkState.Connecting)?.peer?.takeIf { it.id == endpointId }
                ?: (current as? LinkState.Incoming)?.peer?.takeIf { it.id == endpointId }
            val peer = pending ?: nearby[endpointId] ?: PeerDevice(endpointId, endpointId)
            val isRemembered = endpointId == rememberedEndpoint
            rememberedEndpoint = null
            if (resolution.status.isSuccess) {
                connectedEndpoint = endpointId
                client.stopAdvertising()
                client.stopDiscovery()
                // Discovery has stopped, so nothing will report these leaving: forget them, or a later search would
                // offer phones that may no longer be there.
                nearby.clear()
                val shake = Handshake(endpointId, peer, isConfirmed = !isRemembered, nonce = memory.random(NonceBytes))
                handshake = shake
                sendFrame(endpointId, LinkFrame.Hello(memory.ownId, shake.nonce))
                if (isRemembered) {
                    // Not linked until it proves it is the remembered phone.
                    shake.timeout = scope.launch {
                        delay(ProofTimeoutMillis)
                        if (!shake.isProven) refuse(endpointId)
                    }
                } else {
                    _state.value = LinkState.Connected(peer)
                }
            } else if (isRemembered) {
                // It didn't work out by itself; nothing to show, keep looking.
                _state.value = afterAttempt()
            } else if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                // The other person said no (or this one did): carry on looking.
                _state.value = afterAttempt()
            } else {
                _state.value = LinkState.Failed(nearbyError(resolution.status.statusCode))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint != endpointId) return
            dropLink()
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            if (connectedEndpoint == endpointId) media.onBandwidthChanged(info)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (endpointId != connectedEndpoint) return
            val bytes = if (payload.type == Payload.Type.BYTES) payload.asBytes() else null
            val isLinked = _state.value is LinkState.Connected
            if (bytes != null && isLinkFrame(bytes)) {
                when (val frame = decodeLinkFrame(bytes)) {
                    null -> Unit
                    is LinkFrame.RangeRequest, is LinkFrame.RangeOpened, is LinkFrame.RangeRefused,
                    is LinkFrame.DigestRequest, is LinkFrame.Digest,
                    ->
                        if (isLinked) media.onFrame(frame)
                    else -> onFrame(endpointId, frame)
                }
                return
            }
            // Nothing gets through until the link counts (a remembered phone that hasn't proven itself yet).
            if (!isLinked) {
                if (payload.type != Payload.Type.BYTES) client.cancelPayload(payload.id)
                return
            }
            when (payload.type) {
                Payload.Type.BYTES -> {
                    if (bytes == null) return
                    val message = (SlideMessageCodec.decode(bytes) as? Result.Success)?.data ?: return
                    noteGap(message)
                    received.tryEmit(message)
                }
                // Its bytes are still arriving; it is usable once its transfer reports success.
                Payload.Type.FILE -> incomingFiles[payload.id] = payload
                // A range of a shared file, read as it arrives.
                Payload.Type.STREAM -> media.onStream(payload)
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (media.onTransferUpdate(update)) return
            val id = update.payloadId
            val incoming = incomingFiles[id]
            // Message payloads report too; only file transfers are tracked.
            val outcome = if (incoming != null) transfer(id) else transfers[id] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    incomingFiles.remove(id)
                    val location = incoming?.asFile()?.let { file ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            file.asUri()
                        } else {
                            @Suppress("DEPRECATION")
                            file.asJavaFile()?.let(Uri::fromFile)
                        }
                    }
                    if (incoming != null && location == null) {
                        outcome.complete(Result.Failure(DataError.Network.UNKNOWN))
                    } else {
                        outcome.complete(Result.Success(location?.toString()))
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    incomingFiles.remove(id)
                    outcome.complete(Result.Failure(DataError.Network.UNKNOWN))
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    incomingFiles.remove(id)
                    outcome.complete(Result.Failure(DataError.Network.CANCELLED))
                }
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val total = update.totalBytes
                    if (total > 0) progressListeners[id]?.invoke((update.bytesTransferred.toFloat() / total).coerceIn(0f, 1f))
                }
                else -> Unit
            }
        }
    }
}

/** One link's introductions. [isConfirmed]: both users checked the code; otherwise the other end must prove itself. */
private class Handshake(val endpointId: String, val peer: PeerDevice, val isConfirmed: Boolean, val nonce: ByteArray) {
    var peerId: ByteArray? = null
    var isProven = false
    var timeout: Job? = null
}

/** Runs a Nearby call, turning its failures into [DataError.Network]. */
private suspend fun nearbyCall(block: suspend () -> Unit): EmptyResult<DataError.Network> =
    try {
        block()
        Result.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        when (e.statusCode) {
            // Asking again for what is already running is fine.
            ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING,
            ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING,
            ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT,
            -> Result.Success(Unit)
            else -> Result.Failure(nearbyError(e.statusCode))
        }
    } catch (_: Exception) {
        Result.Failure(DataError.Network.UNKNOWN)
    }

internal fun nearbyError(statusCode: Int): DataError.Network = when (statusCode) {
    // Bluetooth and radio errors share one code.
    ConnectionsStatusCodes.STATUS_RADIO_ERROR -> DataError.Network.RADIO_OFF
    ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
    ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
    ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
    ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
    ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
    ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
    ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
    ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
    ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
    ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
    -> DataError.Network.PERMISSION_MISSING
    ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN,
    ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT,
    -> DataError.Network.DISCONNECTED
    else -> DataError.Network.UNKNOWN
}

/** The name people gave their phone ("Khalid's Pixel"), or its model. */
private fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
