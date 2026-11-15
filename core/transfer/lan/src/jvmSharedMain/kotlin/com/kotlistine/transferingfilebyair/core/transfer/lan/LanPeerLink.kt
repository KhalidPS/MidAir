package com.kotlistine.transferingfilebyair.core.transfer.lan

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.data.SlideMessageCodec
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.InviteLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private const val ConnectTimeoutMillis = 3_000

/** The key exchange and the proofs must be done by then, or the connection is dropped. */
private const val HandshakeTimeoutMillis = 5_000

/** A linked (or pairing) control connection sends a heartbeat this often… */
private const val HeartbeatMillis = 2_000L

/** …and counts as gone after this long without any frame (the Wi-Fi went away without closing it). */
private const val ControlReadTimeoutMillis = 8_000

/** A file waits this long for the files' connection of a link that just came up. */
private const val FilesConnectionWaitMillis = 5_000L

private const val ChunkBytes = 64 * 1024

/** [PeerDevice.id]s of this link start with this, then the device's install id in hex. */
private const val PeerIdPrefix = "lan:"

/**
 * The link to a device on the same local network: a phone and a laptop, which can't use Nearby. Both announce
 * themselves over mDNS ([LanDiscovery]) while pairing or looking for the device they paired with, and listen on a
 * TCP port. Pairing works like the phones' Nearby link: one user asks ([connect]); both screens show the same code
 * (from the key exchange, see [SecureChannel]) and each pairs only once its own user [accept]s. A paired device links
 * up again by itself, proving the key they agreed on.
 *
 * A link is two encrypted connections, opened by the end that asked: one for pairing and card messages (small, sent
 * at once), and one for the bytes of files, so a big file never holds up a card's moves.
 *
 * The state changes one at a time on [confined]; the sockets are read on threads of their own.
 */
class LanPeerLink(
    private val memory: LanMemory,
    private val discovery: LanDiscovery,
    private val files: LanFiles,
    ownName: String,
    /**
     * This device decides where the other one sits ([placePeer]): a laptop, whose screen can't take part in the
     * phones' line-up swipe. The other device takes the placement it is told.
     */
    private val ownsPlacement: Boolean = false,
    /** Where what happens is told (logcat on a phone, the console on a laptop). */
    private val log: (String) -> Unit = {},
) : PeerLink, InviteLink {

    private val identity = LanIdentity(memory.ownId, ownName)
    private val ownHex = memory.ownId.toHex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val confined = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val received = MutableSharedFlow<SlideMessage>(extraBufferCapacity = 256)

    // Changed on [confined] only.
    private var server: ServerSocket? = null
    private var browsing: Job? = null
    private var isAdvertising = false
    private val found = LinkedHashMap<String, LanService>()
    private var isSearching = false
    private var wantsReconnect = false
    private var dialing: Job? = null

    /** A first meeting waiting for the users (or for the other one). */
    private var attempt: Connection? = null

    /** The secret of the invitation this device has on show ([invite]), until it is used or withdrawn. */
    @Volatile
    private var inviteToken: ByteArray? = null

    /** The live link; read from the sockets' threads too. */
    @Volatile
    private var session: Connection? = null

    private val transfers = ConcurrentHashMap<Long, CompletableDeferred<Result<String?, DataError.Network>>>()
    private val progressListeners = ConcurrentHashMap<Long, (Long) -> Unit>()
    private val incoming = ConcurrentHashMap<Long, IncomingTransfer>()
    private val cancelled: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** The port this device listens on, once it does (for a QR code to point at). */
    val port: Int? get() = server?.localPort

    /** The laptop places the phone (its Phone menu): no swipe across the two screens. */
    override val linesUpBySwipe: Boolean = false

    private val mediaChannel = LanMediaChannel(files, scope) {
        session?.let { link ->
            withTimeoutOrNull(FilesConnectionWaitMillis) {
                try {
                    link.files.await()
                } catch (e: CancellationException) {
                    // The link ended (its files' connection was called off), unless the caller itself was.
                    currentCoroutineContext().ensureActive()
                    null
                }
            }
        }
    }

    /** Reads the other device's files a piece at a time, over the files' connection ([LanMediaChannel]). */
    override val media: MediaChannel = mediaChannel

    override val transport: Transport = object : Transport {
        override val incoming: Flow<SlideMessage> = received

        override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
            val link = session ?: return Result.Failure(DataError.Network.DISCONNECTED)
            val bytes = when (val encoded = SlideMessageCodec.encode(message)) {
                is Result.Failure -> return encoded
                is Result.Success -> encoded.data
            }
            return withContext(Dispatchers.IO) {
                try {
                    link.channel.send(LanFrame.Message(bytes))
                    Result.Success(Unit)
                } catch (_: IOException) {
                    Result.Failure(DataError.Network.DISCONNECTED)
                }
            }
        }
    }

    override suspend fun startSearching(): EmptyResult<DataError.Network> = withContext(confined) {
        if (session != null || attempt != null) return@withContext Result.Success(Unit)
        if (!ensureServer()) return@withContext Result.Failure(DataError.Network.UNKNOWN)
        isSearching = true
        _state.value = afterAttempt()
        refreshPresence()
        Result.Success(Unit)
    }

    override fun stopSearching() {
        scope.launch(confined) {
            isSearching = false
            found.clear()
            attempt?.let { pending ->
                attempt = null
                pending.hangUp(LanFrame.Decline)
            }
            if (session == null) {
                dialing?.cancel()
                dialing = null
                _state.value = LinkState.Idle
            }
            refreshPresence()
        }
    }

    override suspend fun reconnect() = withContext(confined) {
        wantsReconnect = true
        if (memory.peer != null) ensureServer()
        refreshPresence()
    }

    override fun pauseReconnecting() {
        scope.launch(confined) {
            wantsReconnect = false
            refreshPresence()
        }
    }

    override suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network> = withContext(confined) {
        val service = found[peer.id] ?: return@withContext Result.Failure(DataError.Network.DISCONNECTED)
        _state.value = LinkState.Connecting(peer)
        dial(service.host, service.port, userAsked = true)
        Result.Success(Unit)
    }

    /**
     * An invitation to pair, for this device to show as a QR code: listening on every address in [hosts]. Replaces
     * any earlier one; it pairs once, with whoever scans it ([joinInvite]), without either user checking a code.
     */
    suspend fun invite(hosts: List<String>): LanInvite? = withContext(confined) {
        if (hosts.isEmpty() || !ensureServer()) return@withContext null
        val port = server?.localPort ?: return@withContext null
        val token = LanCrypto.random(InviteTokenBytes)
        inviteToken = token
        LanInvite(hosts, port, ownHex, identity.name, token)
    }

    /** Takes back the invitation on show (its QR code was closed): nobody can pair with it any more. */
    fun withdrawInvite() {
        scope.launch(confined) { inviteToken = null }
    }

    /** Pairs with the device whose invitation was scanned ([text]), if it is one; both prove they know its secret. */
    override suspend fun joinInvite(text: String): EmptyResult<DataError.Network> = withContext(confined) {
        val invite = LanInvite.parse(text) ?: return@withContext Result.Failure(DataError.Network.MALFORMED_MESSAGE)
        if (session != null) return@withContext Result.Failure(DataError.Network.UNKNOWN)
        attempt?.let { pending ->
            attempt = null
            pending.hangUp(LanFrame.Decline)
        }
        _state.value = LinkState.Connecting(PeerDevice(PeerIdPrefix + invite.deviceId, invite.name))
        dial(invite.hosts, invite.port, userAsked = true, inviteKey = invite.token)
        Result.Success(Unit)
    }

    override suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network> = withContext(confined) {
        val pending = attempt?.takeIf { it.peer.id == peer.id && _state.value is LinkState.Incoming }
            ?: return@withContext Result.Failure(DataError.Network.DISCONNECTED)
        pending.acceptedHere = true
        _state.value = LinkState.Connecting(peer, pending.channel.code)
        pending.sendQuietly(LanFrame.Accept)
        if (pending.acceptedThere) linkUp(pending, isNewPairing = true)
        Result.Success(Unit)
    }

    override fun decline(peer: PeerDevice) {
        scope.launch(confined) {
            val pending = attempt?.takeIf { it.peer.id == peer.id } ?: return@launch
            attempt = null
            pending.hangUp(LanFrame.Decline)
            _state.value = afterAttempt()
        }
    }

    override fun disconnect() {
        scope.launch(confined) {
            memory.forget()
            session?.hangUp(LanFrame.Forget)
            dropLink()
        }
    }

    override suspend fun sendFile(
        uri: String,
        announce: suspend (transferId: Long) -> Unit,
        onProgress: (bytesSent: Long) -> Unit,
    ): EmptyResult<DataError.Network> {
        val link = session ?: return Result.Failure(DataError.Network.DISCONNECTED)
        val source = withContext(Dispatchers.IO) { files.open(uri) } ?: return Result.Failure(DataError.Network.UNKNOWN)
        val transferId = Random.nextLong(1, Long.MAX_VALUE)
        val outcome = transfer(transferId)
        try {
            announce(transferId)
            // Stopped while it was being announced: nothing goes.
            if (outcome.isCompleted) return outcome.await().asSendResult()
            val channel = withTimeoutOrNull(FilesConnectionWaitMillis) { link.files.await() }
                ?: return Result.Failure(DataError.Network.DISCONNECTED)
            withContext(Dispatchers.IO) {
                channel.send(LanFrame.FileStart(transferId, source.sizeBytes))
                val buffer = ByteArray(ChunkBytes)
                var sent = 0L
                while (!outcome.isCompleted) {
                    val read = source.stream.read(buffer)
                    if (read < 0) break
                    channel.send(LanFrame.FileChunk(transferId, buffer.copyOf(read)))
                    sent += read
                    onProgress(sent)
                }
                if (!outcome.isCompleted) channel.send(LanFrame.FileEnd(transferId))
            }
            return outcome.await().asSendResult()
        } catch (_: IOException) {
            link.sendQuietly(LanFrame.FileCancel(transferId))
            return Result.Failure(DataError.Network.DISCONNECTED)
        } catch (e: CancellationException) {
            // Whoever sent it went away: the other end mustn't keep half a file waiting.
            link.sendQuietly(LanFrame.FileCancel(transferId))
            throw e
        } finally {
            runCatching { source.stream.close() }
            transfers.remove(transferId)
        }
    }

    override suspend fun receiveFile(transferId: Long, onProgress: (Long) -> Unit): Result<String, DataError.Network> {
        progressListeners[transferId] = onProgress
        incoming[transferId]?.let { onProgress(it.receivedBytes) }
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

    /**
     * The other device sits beside this one's [side] edge, its screen top [verticalOffsetMm] below this one's. Kept,
     * and told to the other device (now, and whenever they link up), so its cards leave through the edge that faces
     * this one and arrive at the height they left.
     */
    fun placePeer(side: ExitEdge, verticalOffsetMm: Float) {
        memory.update(memory.link.value.copy(peerSide = side, verticalOffsetMm = verticalOffsetMm))
        placementForPeer()?.let { placement -> session?.sendQuietly(placement) }
    }

    /** The other device's view of [placePeer]: its own edge that faces this one, and this one's top below its own. */
    private fun placementForPeer(): LanFrame.Placement? {
        if (!ownsPlacement) return null
        val link = memory.link.value
        val side = link.peerSide ?: return null
        return LanFrame.Placement(side.opposite, -link.verticalOffsetMm)
    }

    override fun cancelTransfer(transferId: Long) {
        cancelled += transferId
        incoming.remove(transferId)?.target?.discard?.invoke()
        transfer(transferId).complete(Result.Failure(DataError.Network.CANCELLED))
        session?.sendQuietly(LanFrame.FileCancel(transferId))
    }

    // ---- Being found, and finding ----

    /** Visible and looking while pairing, or while there is a paired device to link up with again. */
    private fun refreshPresence() {
        val wanted = session == null && (isSearching || (wantsReconnect && memory.peer != null))
        val port = server?.localPort
        if (wanted && port != null && !isAdvertising) {
            isAdvertising = true
            scope.launch { runCatching { discovery.advertise(ownHex, identity.name, port) } }
        } else if (!wanted && isAdvertising) {
            isAdvertising = false
            scope.launch { runCatching { discovery.stopAdvertising() } }
        }
        if (wanted && browsing == null) {
            browsing = scope.launch {
                discovery.browse().collect { services -> withContext(confined) { onServices(services) } }
            }
        } else if (!wanted) {
            browsing?.cancel()
            browsing = null
            found.clear()
        }
    }

    private fun onServices(services: List<LanService>) {
        found.clear()
        services.filter { it.deviceId != ownHex }.forEach { found[PeerIdPrefix + it.deviceId] = it }
        val current = _state.value
        if (session == null && attempt == null && (current is LinkState.Searching || current is LinkState.Found)) {
            _state.value = afterAttempt()
        }
        // The paired device is back: the one with the smaller id calls it.
        val trusted = memory.peer ?: return
        if (!wantsReconnect || session != null || attempt != null || dialing != null) return
        if (!dialsToReconnect(memory.ownId, trusted.id)) return
        val service = services.firstOrNull { it.deviceId == trusted.id.toHex() } ?: return
        log("Linking up again with ${service.name} at ${service.host}:${service.port}")
        dial(service.host, service.port, userAsked = false)
    }

    private fun afterAttempt(): LinkState = when {
        !isSearching -> LinkState.Idle
        else -> found.values.lastOrNull()?.let { LinkState.Found(PeerDevice(PeerIdPrefix + it.deviceId, it.name)) }
            ?: LinkState.Searching
    }

    // ---- Connections ----

    private fun ensureServer(): Boolean {
        if (server != null) return true
        val listening = runCatching { ServerSocket(0) }.getOrElse {
            log("Can't listen: $it")
            return false
        }
        server = listening
        log("Listening on port ${listening.localPort}")
        scope.launch {
            while (isActive) {
                val socket = runCatching { listening.accept() }.getOrNull() ?: break
                launch { onIncomingSocket(socket) }
            }
        }
        return true
    }

    private fun dial(host: String, port: Int, userAsked: Boolean) = dial(listOf(host), port, userAsked, inviteKey = null)

    /**
     * Calls the device at the first of [hosts] that answers on [port]. [inviteKey]: the secret of a scanned
     * invitation, which this end proves it knows.
     */
    private fun dial(hosts: List<String>, port: Int, userAsked: Boolean, inviteKey: ByteArray?) {
        dialing?.cancel()
        dialing = scope.launch {
            val self = coroutineContext[Job]
            var connected: Socket? = null
            // Cancelling doesn't interrupt a socket blocked in a read: closing it does.
            self?.invokeOnCompletion { cause -> if (cause != null) runCatching { connected?.close() } }
            try {
                val (host, socket) = connectToFirst(hosts, port)
                connected = socket
                socket.prepare()
                val channel = SecureChannel.open(socket, identity, Purpose.CONTROL, isDialer = true)
                onControlChannel(channel, isDialer = true, userAsked = userAsked, host = host, port = port, inviteKey)
            } catch (e: IOException) {
                runCatching { connected?.close() }
                log("Couldn't link up with ${hosts.joinToString()} on $port: $e")
                withContext(confined) {
                    if (userAsked && _state.value is LinkState.Connecting && attempt == null && session == null) {
                        _state.value = LinkState.Failed(DataError.Network.DISCONNECTED)
                    }
                }
            } finally {
                withContext(confined + NonCancellable) {
                    if (dialing === self) dialing = null
                }
            }
        }
    }

    /** The first of [hosts] that takes a connection on [port] (a laptop has an address on each of its networks). */
    private fun connectToFirst(hosts: List<String>, port: Int): Pair<String, Socket> {
        var lastError: IOException = IOException("No address to call")
        for (host in hosts) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), ConnectTimeoutMillis)
                return host to socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                lastError = e
            }
        }
        throw lastError
    }

    private suspend fun onIncomingSocket(socket: Socket) {
        try {
            socket.prepare()
            val channel = SecureChannel.open(socket, identity, purpose = null, isDialer = false)
            when (channel.peer.purpose) {
                Purpose.CONTROL -> onControlChannel(channel, isDialer = false, userAsked = false, host = null, port = 0)
                Purpose.FILES -> onIncomingFilesChannel(channel)
            }
        } catch (e: IOException) {
            runCatching { socket.close() }
            log("Dropped an incoming connection: $e")
        }
    }

    /**
     * Both ends show who they are; then the connection links up, waits for the users, or is dropped. [inviteKey]: the
     * dialer scanned this end's invitation and knows its secret.
     */
    private suspend fun onControlChannel(
        channel: SecureChannel,
        isDialer: Boolean,
        userAsked: Boolean,
        host: String?,
        port: Int,
        inviteKey: ByteArray? = null,
    ) {
        val trusted = memory.peer?.takeIf { it.id.contentEquals(channel.peerId) }
        // The secret an invitation proof is checked against: the one scanned, or the one this end has on show.
        val invitation = inviteKey ?: inviteToken
        // The caller shows itself first, so the called end can answer in kind.
        val heard = if (isDialer) null else channel.receiveAuth()
        val mine = when {
            inviteKey != null -> AuthMode.QR
            // A phone that scanned the code on show pairs through it, even one paired before: it may have forgotten
            // this end, and answering it as a paired device would leave the two with nothing in common.
            heard?.mode == AuthMode.QR && invitation != null -> AuthMode.QR
            trusted != null -> AuthMode.TRUSTED
            invitation != null -> AuthMode.QR
            else -> AuthMode.PAIR
        }
        val mac = when (mine) {
            AuthMode.TRUSTED -> channel.proof(requireNotNull(trusted).key, memory.ownId)
            AuthMode.QR -> channel.proof(requireNotNull(invitation), memory.ownId)
            AuthMode.PAIR, AuthMode.SESSION -> ByteArray(0)
        }
        channel.send(LanFrame.Auth(mine, mac))
        val theirAuth = heard ?: channel.receiveAuth()
        val theirs = when (theirAuth.mode) {
            AuthMode.TRUSTED -> if (trusted == null) {
                AuthMode.PAIR
            } else {
                AuthMode.TRUSTED.takeIf { LanCrypto.same(theirAuth.mac, channel.proof(trusted.key, channel.peerId)) }
            }
            AuthMode.QR -> if (invitation == null) {
                AuthMode.PAIR
            } else {
                AuthMode.QR.takeIf { LanCrypto.same(theirAuth.mac, channel.proof(invitation, channel.peerId)) }
            }
            AuthMode.PAIR -> AuthMode.PAIR
            AuthMode.SESSION -> null
        }
        val peer = PeerDevice(PeerIdPrefix + channel.peerId.toHex(), channel.peer.name)
        val connection = Connection(channel, peer, isDialer, host, port)

        withContext(confined) {
            val current = session
            // The paired device came back while this end still thought the old connection was up: it wasn't.
            val bothProved = mine == theirs && (mine == AuthMode.TRUSTED || mine == AuthMode.QR)
            if (current != null && current.peer.id == peer.id && bothProved) dropLink(reconnectAfter = false)
            val isFree = session == null && attempt == null
            val mayAskUsers = isFree && if (isDialer) {
                // A scanned code pairs through its secret or not at all: no falling back to the code check.
                userAsked && inviteKey == null && (_state.value as? LinkState.Connecting)?.peer?.id == peer.id
            } else {
                isSearching && dialing == null
            }
            val outcome = if (isFree) decideAuth(mine, theirs, mayAskUsers) else AuthOutcome.REFUSE
            log("${if (isDialer) "Called" else "Called by"} ${peer.name}: mine $mine, theirs $theirs → $outcome")
            when (outcome) {
                AuthOutcome.LINK -> linkUp(connection, isNewPairing = false)
                AuthOutcome.LINK_NEW_PAIRING -> {
                    // An invitation pairs once.
                    if (!isDialer) inviteToken = null
                    linkUp(connection, isNewPairing = true)
                }
                AuthOutcome.ASK_USERS -> {
                    attempt = connection
                    _state.value = LinkState.Incoming(peer, channel.code)
                }
                AuthOutcome.REFUSE -> {
                    channel.close()
                    if (isDialer && userAsked && isFree) {
                        // A scanned code that didn't pair (taken back, or not this laptop's) says so.
                        _state.value = if (inviteKey != null) LinkState.Failed(DataError.Network.DISCONNECTED) else afterAttempt()
                    }
                    return@withContext
                }
            }
            channel.setReadTimeout(ControlReadTimeoutMillis)
            readControl(connection)
            keepAlive(connection)
        }
    }

    private fun SecureChannel.receiveAuth(): LanFrame.Auth =
        receive() as? LanFrame.Auth ?: throw IOException("Expected the other end's proof")

    /** On [confined]. */
    private fun linkUp(connection: Connection, isNewPairing: Boolean) {
        attempt = null
        session = connection
        connection.isNewPairing = isNewPairing
        _state.value = LinkState.Connected(connection.peer)
        log("Linked with ${connection.peer.name}")
        if (isNewPairing && handsOutKey(memory.ownId, connection.channel.peerId)) {
            val key = LanCrypto.random(TrustKeyBytes)
            memory.trust(LanTrustedPeer(connection.channel.peerId, connection.peer.name, key))
            connection.sendQuietly(LanFrame.Trust(key))
        }
        placementForPeer()?.let(connection::sendQuietly)
        if (connection.isDialer) openFilesChannel(connection)
        refreshPresence()
    }

    /** The live link is gone: every transfer on it fails, and the paired device is looked for again. On [confined]. */
    private fun dropLink(reconnectAfter: Boolean = true) {
        val link = session ?: return
        session = null
        link.close()
        abandonTransfers()
        mediaChannel.onLinkDropped()
        _state.value = LinkState.Idle
        log("Link with ${link.peer.name} ended")
        if (reconnectAfter) refreshPresence()
    }

    private fun readControl(connection: Connection) = scope.launch {
        try {
            while (true) connection.channel.receive()?.let { onControlFrame(connection, it) }
        } catch (_: IOException) {
            withContext(confined) {
                when {
                    attempt === connection -> {
                        attempt = null
                        _state.value = afterAttempt()
                    }
                    session === connection -> dropLink()
                }
            }
        }
    }

    private fun keepAlive(connection: Connection) = scope.launch {
        while (isActive && !connection.isClosed) {
            delay(HeartbeatMillis)
            try {
                connection.channel.send(LanFrame.Heartbeat)
            } catch (_: IOException) {
                connection.close()
            }
        }
    }

    private suspend fun onControlFrame(connection: Connection, frame: LanFrame) {
        when (frame) {
            is LanFrame.Message -> if (session === connection) {
                (SlideMessageCodec.decode(frame.bytes) as? Result.Success)?.data?.let(received::tryEmit)
            }
            LanFrame.Accept -> withContext(confined) {
                if (attempt !== connection) return@withContext
                connection.acceptedThere = true
                if (connection.acceptedHere) linkUp(connection, isNewPairing = true)
            }
            LanFrame.Decline -> withContext(confined) {
                if (attempt !== connection) return@withContext
                attempt = null
                connection.close()
                _state.value = afterAttempt()
            }
            is LanFrame.Trust -> withContext(confined) {
                // Only on a new pairing, and only from the end that hands out keys.
                val peerId = connection.channel.peerId
                if (session !== connection || !connection.isNewPairing || handsOutKey(memory.ownId, peerId)) return@withContext
                if (frame.key.size == TrustKeyBytes) memory.trust(LanTrustedPeer(peerId, connection.peer.name, frame.key))
            }
            LanFrame.Forget -> withContext(confined) {
                if (session === connection) memory.forget()
            }
            // The laptop says where this device sits: its cards leave through that edge from now on.
            is LanFrame.Placement -> if (session === connection && !ownsPlacement) {
                log("Placed: the other device is beside my ${frame.side} edge, ${frame.verticalOffsetMm} mm")
                memory.update(memory.link.value.copy(peerSide = frame.side, verticalOffsetMm = frame.verticalOffsetMm))
            }
            is LanFrame.FileDone -> transfers[frame.transferId]?.complete(Result.Success(null))
            is LanFrame.FileCancel -> {
                cancelled += frame.transferId
                incoming.remove(frame.transferId)?.target?.discard?.invoke()
                transfer(frame.transferId).complete(Result.Failure(DataError.Network.CANCELLED))
            }
            LanFrame.Heartbeat, is LanFrame.Auth, is LanFrame.FileStart, is LanFrame.FileChunk, is LanFrame.FileEnd,
            is LanFrame.MediaRead, is LanFrame.MediaData, is LanFrame.MediaDone, is LanFrame.MediaCancel,
            -> Unit
        }
    }

    // ---- Files ----

    /** The end that asked opens the files' connection, to the address it called, once the link is up. */
    private fun openFilesChannel(connection: Connection) {
        val host = connection.host ?: return
        scope.launch {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, connection.port), ConnectTimeoutMillis)
                socket.prepare()
                val channel = SecureChannel.open(socket, identity, Purpose.FILES, isDialer = true)
                if (!channel.peerId.contentEquals(connection.channel.peerId)) throw IOException("A different device")
                channel.send(LanFrame.Auth(AuthMode.SESSION, channel.proof(connection.channel.sessionSecret, memory.ownId)))
                val theirs = channel.receive() as? LanFrame.Auth
                val expected = channel.proof(connection.channel.sessionSecret, channel.peerId)
                if (theirs?.mode != AuthMode.SESSION || !LanCrypto.same(theirs.mac, expected)) {
                    throw IOException("The files' connection failed its proof")
                }
                attachFilesChannel(connection, channel)
            } catch (e: IOException) {
                runCatching { socket.close() }
                log("No files' connection: $e")
            }
        }
    }

    private fun onIncomingFilesChannel(channel: SecureChannel) {
        val link = session?.takeIf { it.channel.peerId.contentEquals(channel.peerId) }
            ?: throw IOException("Files' connection with no link")
        val theirs = channel.receive() as? LanFrame.Auth
        val expected = channel.proof(link.channel.sessionSecret, channel.peerId)
        if (theirs?.mode != AuthMode.SESSION || !LanCrypto.same(theirs.mac, expected)) {
            throw IOException("The files' connection failed its proof")
        }
        channel.send(LanFrame.Auth(AuthMode.SESSION, channel.proof(link.channel.sessionSecret, memory.ownId)))
        attachFilesChannel(link, channel)
    }

    private fun attachFilesChannel(connection: Connection, channel: SecureChannel) {
        channel.setReadTimeout(0)
        connection.filesChannel = channel
        // The link may have ended meanwhile (its close cancelled [Connection.files]).
        if (connection.isClosed || !connection.files.complete(channel)) {
            channel.close()
            return
        }
        scope.launch { readFiles(connection, channel) }
    }

    private fun readFiles(connection: Connection, channel: SecureChannel) {
        try {
            while (true) {
                when (val frame = channel.receive()) {
                    is LanFrame.FileStart -> if (frame.transferId !in cancelled) {
                        incoming[frame.transferId] =
                            IncomingTransfer(files.createIncoming(frame.transferId), frame.sizeBytes)
                    }
                    is LanFrame.FileChunk -> incoming[frame.transferId]?.let { transfer ->
                        try {
                            transfer.target.stream.write(frame.bytes)
                            transfer.receivedBytes += frame.bytes.size
                            progressListeners[frame.transferId]?.invoke(transfer.receivedBytes)
                        } catch (_: IOException) {
                            failIncoming(connection, frame.transferId)
                        }
                    }
                    is LanFrame.FileEnd -> incoming.remove(frame.transferId)?.let { transfer ->
                        val kept = runCatching { transfer.target.stream.close() }.isSuccess
                        if (kept && transfer.receivedBytes == transfer.sizeBytes) {
                            transfer(frame.transferId).complete(Result.Success(transfer.target.location))
                            connection.sendQuietly(LanFrame.FileDone(frame.transferId))
                        } else {
                            transfer.target.discard()
                            transfer(frame.transferId).complete(Result.Failure(DataError.Network.UNKNOWN))
                            connection.sendQuietly(LanFrame.FileCancel(frame.transferId))
                        }
                    }
                    is LanFrame.MediaRead, is LanFrame.MediaData, is LanFrame.MediaDone, is LanFrame.MediaCancel ->
                        mediaChannel.onFrame(channel, frame)
                    else -> Unit
                }
            }
        } catch (_: IOException) {
            // The link's end is noticed on its control connection, which fails the transfers.
            channel.close()
        }
    }

    /** This end can't keep an incoming file (disk full, folder gone): it stops, and tells the sender. */
    private fun failIncoming(connection: Connection, transferId: Long) {
        cancelled += transferId
        incoming.remove(transferId)?.target?.discard?.invoke()
        transfer(transferId).complete(Result.Failure(DataError.Network.UNKNOWN))
        connection.sendQuietly(LanFrame.FileCancel(transferId))
    }

    private fun transfer(id: Long) = transfers.getOrPut(id) { CompletableDeferred() }

    private fun abandonTransfers() {
        transfers.values.forEach { it.complete(Result.Failure(DataError.Network.DISCONNECTED)) }
        incoming.values.forEach { it.target.discard() }
        incoming.clear()
    }

    private fun Result<String?, DataError.Network>.asSendResult(): EmptyResult<DataError.Network> = when (this) {
        is Result.Success -> Result.Success(Unit)
        is Result.Failure -> this
    }

    private fun Socket.prepare() {
        tcpNoDelay = true
        soTimeout = HandshakeTimeoutMillis
    }

    /** One connection to the other device, from the key exchange until it ends. */
    private inner class Connection(
        val channel: SecureChannel,
        val peer: PeerDevice,
        val isDialer: Boolean,
        /** Where the dialer reached the other end, for the files' connection. */
        val host: String?,
        val port: Int,
    ) {
        @Volatile
        var acceptedHere = false

        @Volatile
        var acceptedThere = false

        @Volatile
        var isNewPairing = false

        @Volatile
        var isClosed = false
            private set

        val files = CompletableDeferred<SecureChannel>()

        @Volatile
        var filesChannel: SecureChannel? = null

        /**
         * Frames sent off the caller's thread, in the order given: a key ([LanFrame.Trust]) must never overtake the
         * [LanFrame.Accept] before it. A null closes the connection once everything before it went.
         */
        private val outbox = Channel<LanFrame?>(Channel.UNLIMITED)

        init {
            scope.launch {
                for (frame in outbox) {
                    if (frame == null) break
                    try {
                        channel.send(frame)
                    } catch (_: IOException) {
                        break
                    }
                }
                close()
            }
        }

        /** Sends [frame] in turn; a failure means the connection is ending anyway. */
        fun sendQuietly(frame: LanFrame) {
            outbox.trySend(frame)
        }

        /** Tells the other end [frame] (if it still listens), then closes. */
        fun hangUp(frame: LanFrame) {
            outbox.trySend(frame)
            outbox.trySend(null)
        }

        fun close() {
            isClosed = true
            outbox.close()
            channel.close()
            filesChannel?.close()
            files.cancel()
        }
    }

    private class IncomingTransfer(val target: IncomingFile, val sizeBytes: Long) {
        @Volatile
        var receivedBytes = 0L
    }
}
