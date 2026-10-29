package com.kotlistine.transferingfilebyair.feature.shelf.data

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage.FileRoute.Route
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.ReceivedFileStore
import com.kotlistine.transferingfilebyair.feature.shelf.domain.Delivery
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How long a let-go card waits to hear which incoming file is its own. */
private val OfferWait = 15.seconds

/**
 * How long a sender waits to hear whether the peer takes the file from its video itself. The answer leaves as soon as
 * the commit lands; a peer that never answers doesn't know how, and gets the file sent.
 */
private val RouteWait = 5.seconds

/**
 * The paired device as the shelf sees it, on a [PeerLink] (Nearby on Android). A handed-over card's file follows
 * it: the sender announces the transfer ([SlideMessage.FileOffer]) and streams the bytes; the receiver matches the
 * transfer to the card, waits for every byte, keeps the file and puts it on its shelf.
 *
 * When the card carried its playing video, the receiver has most of the file already (it read it to play it), so it
 * says how the file goes ([SlideMessage.FileRoute]): it takes the rest over the [MediaChannel] itself, so no second
 * transfer competes with the video, or it asks for the file to be sent as usual.
 */
class LinkedSlidePeer(
    private val peerLink: PeerLink,
    private val repository: ShelfRepository,
    private val store: ReceivedFileStore,
    /** How the screens sit, once lined up in pairing. */
    private val geometry: LinkGeometry,
    scope: CoroutineScope,
) : SlidePeer {

    override val transport: Transport get() = peerLink.transport

    /** The defaults (any edge leads to the peer, an 8 mm gap) until the screens are lined up. */
    override val link: SlideLink get() = geometry.link.value

    override val isLinked: Flow<Boolean> = peerLink.state.map { it is LinkState.Connected }.distinctUntilChanged()

    override val media: MediaChannel? get() = peerLink.media

    /** Transfer ids announced by the peer, by the item id on its shelf. */
    private val offers = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The peer's latest word on how the file of each of this device's items goes, by item id. */
    private val routes = MutableStateFlow<Map<String, RouteNote>>(emptyMap())

    /** Items the user stopped before their transfer was known; their receive fails as soon as it is. */
    private val cancelled = MutableStateFlow<Set<String>>(emptySet())

    init {
        peerLink.transport.incoming
            .onEach { message ->
                when (message) {
                    is SlideMessage.FileOffer -> offers.update { it + (message.itemId to message.transferId) }
                    is SlideMessage.FileRoute -> routes.update { it + (message.itemId to RouteNote(message.route)) }
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    override suspend fun send(file: OutgoingFile): EmptyResult<DataError> {
        if (file.peerMayFetch) {
            when (peerFetch(file.itemId)) {
                Route.FETCHED -> return Result.Success(Unit)
                Route.CANCELLED -> return Result.Failure(DataError.Network.CANCELLED)
                null -> return Result.Failure(DataError.Network.DISCONNECTED)
                Route.SEND, Route.FETCHING -> Unit
            }
        }
        return peerLink.sendFile(file.uri) { transferId ->
            peerLink.transport.send(SlideMessage.FileOffer(file.itemId, transferId))
        }
    }

    /**
     * Waits for the peer to say how the file of [itemId] goes, and while it fetches it, until it has. Returns how it
     * ended: [Route.SEND] when the peer wants it sent (or never answered), null when the link went meanwhile.
     */
    private suspend fun peerFetch(itemId: String): Route? = try {
        // Only fresh words count: one left over from an earlier item with the same id must not end this send.
        val heard = routes.map { notes -> notes[itemId]?.takeIf { it.at.elapsedNow() < RouteWait * 2 }?.route }
        val first = withTimeoutOrNull(RouteWait) { heard.filterNotNull().first() } ?: Route.SEND
        if (first != Route.FETCHING) {
            first
        } else {
            merge(
                heard.filter { it != null && it != Route.FETCHING },
                peerLink.state.filter { it !is LinkState.Connected }.map { null },
            ).first()
        }
    } finally {
        routes.update { it - itemId }
    }

    override suspend fun receive(file: IncomingFile, onProgress: (Float) -> Unit): Result<String, DataError> {
        when (file.delivery) {
            Delivery.SENT -> Unit
            Delivery.ASK_TO_SEND -> peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.SEND))
            Delivery.FETCH -> fetch(file, onProgress)?.let { return it }
        }
        val transferId = withTimeoutOrNull(OfferWait) {
            offers.map { it[file.peerItemId] }.filterNotNull().first()
        } ?: return Result.Failure(DataError.Network.DISCONNECTED)
        if (file.peerItemId in cancelled.value) {
            cancelled.update { it - file.peerItemId }
            offers.update { it - file.peerItemId }
            peerLink.cancelTransfer(transferId)
            return Result.Failure(DataError.Network.CANCELLED)
        }
        val location = try {
            peerLink.receiveFile(transferId, onProgress)
        } finally {
            offers.update { it - file.peerItemId }
        }
        val arrivedAt = when (val arrived = location) {
            is Result.Failure -> return arrived
            is Result.Success -> arrived.data
        }
        return keep(file, arrivedAt)
    }

    /**
     * Takes the file from the peer's video over the media channel. Returns the outcome, or null when the fetch failed
     * with the link still up and the peer was asked to send the file instead.
     */
    private suspend fun fetch(file: IncomingFile, onProgress: (Float) -> Unit): Result<String, DataError>? {
        val media = peerLink.media
        if (media == null) {
            peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.SEND))
            return null
        }
        if (peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.FETCHING)) is Result.Failure) {
            return Result.Failure(DataError.Network.DISCONNECTED)
        }
        val fetched = fetchUnlessStopped(media, file, onProgress)
        if (fetched == null) {
            media.forget(file.peerItemId)
            cancelled.update { it - file.peerItemId }
            peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.CANCELLED))
            return Result.Failure(DataError.Network.CANCELLED)
        }
        val location = when (fetched) {
            is Result.Success -> fetched.data
            is Result.Failure -> {
                media.forget(file.peerItemId)
                if (peerLink.state.value !is LinkState.Connected) return Result.Failure(DataError.Network.DISCONNECTED)
                // Something here went wrong (no room, a copy that didn't match): the usual way may still work.
                peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.SEND))
                return null
            }
        }
        val kept = keep(file, location)
        if (kept is Result.Failure) {
            media.forget(file.peerItemId)
            peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.CANCELLED))
            return kept
        }
        // Only now may the peer let go of its file: this device has it for good.
        peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.FETCHED))
        return kept
    }

    /** [MediaChannel.fetch], unless the user stops it ([cancelReceive]) first: null then. */
    private suspend fun fetchUnlessStopped(
        media: MediaChannel,
        file: IncomingFile,
        onProgress: (Float) -> Unit,
    ): Result<String, DataError>? = coroutineScope {
        if (file.peerItemId in cancelled.value) return@coroutineScope null
        val fetching = async { media.fetch(file.peerItemId, file.sizeBytes, onProgress) }
        val stopper = launch {
            cancelled.first { file.peerItemId in it }
            fetching.cancel()
        }
        try {
            fetching.await()
        } catch (e: CancellationException) {
            // The fetch was stopped, not this receive.
            if (!fetching.isCancelled || !isActive) throw e
            null
        } finally {
            stopper.cancel()
        }
    }

    /**
     * Keeps the file that arrived at [location] and puts it on the shelf; returns its item id. A video read from the
     * peer while it played goes on from the kept copy.
     */
    private suspend fun keep(file: IncomingFile, location: String): Result<String, DataError> {
        val kept = when (val stored = store.keep(location, file.name, file.mimeType)) {
            is Result.Failure -> return stored
            is Result.Success -> stored.data
        }
        if (file.delivery == Delivery.FETCH) peerLink.media?.settle(file.peerItemId, kept)
        val added = repository.addFile(kept)
        if (added is Result.Failure) return added
        val item = repository.observeItems().first().firstOrNull { it.uri == kept }
            ?: return Result.Failure(DataError.Local.FILE_NOT_FOUND)
        return Result.Success(item.id)
    }

    override fun cancelReceive(peerItemId: String) {
        val transferId = offers.value[peerItemId]
        if (transferId != null) peerLink.cancelTransfer(transferId) else cancelled.update { it + peerItemId }
    }
}

/** A [Route] the peer sent, and when it arrived. */
private class RouteNote(val route: Route, val at: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow())
