package com.kotlistine.transferingfilebyair.core.transfer.nearby

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException

/** Only installs of this app find each other. */
private const val ServiceId = "com.kotlistine.transferingfilebyair.slide"

/**
 * [PeerLink] on Nearby Connections: Bluetooth to find the other phone and connect, then an automatic upgrade to
 * Wi-Fi Direct or a hotspot for bandwidth. No shared Wi-Fi network is needed. Point-to-point, since a slide goes to
 * exactly one device. Nearby calls back on the main thread, so all state here changes on it.
 */
internal class NearbyPeerLink(context: Context) : PeerLink {

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
    private val received = MutableSharedFlow<SlideMessage>(extraBufferCapacity = 256)

    override val transport: Transport = object : Transport {
        override val incoming: Flow<SlideMessage> = received

        override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
            val endpoint = connectedEndpoint ?: return Result.Failure(DataError.Network.DISCONNECTED)
            val bytes = when (val encoded = SlideMessageCodec.encode(message)) {
                is Result.Failure -> return encoded
                is Result.Success -> encoded.data
            }
            return nearbyCall { client.sendPayload(endpoint, Payload.fromBytes(bytes)).await() }
        }
    }

    override suspend fun startSearching(): EmptyResult<DataError.Network> {
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
        _state.value = LinkState.Idle
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

    override fun disconnect() {
        connectedEndpoint?.let(client::disconnectFromEndpoint)
        connectedEndpoint = null
        _state.value = LinkState.Idle
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
            if (resolution.status.isSuccess) {
                connectedEndpoint = endpointId
                client.stopAdvertising()
                client.stopDiscovery()
                // Discovery has stopped, so nothing will report these leaving: forget them, or a later search would
                // offer phones that may no longer be there.
                nearby.clear()
                _state.value = LinkState.Connected(peer)
            } else if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                // The other person said no (or this one did): carry on looking.
                _state.value = afterAttempt()
            } else {
                _state.value = LinkState.Failed(nearbyError(resolution.status.statusCode))
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint != endpointId) return
            connectedEndpoint = null
            _state.value = LinkState.Idle
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (endpointId != connectedEndpoint || payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            (SlideMessageCodec.decode(bytes) as? Result.Success)?.let { received.tryEmit(it.data) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }
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
