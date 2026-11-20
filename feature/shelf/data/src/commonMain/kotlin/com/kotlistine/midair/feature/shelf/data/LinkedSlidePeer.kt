package com.kotlistine.midair.feature.shelf.data

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult
import com.kotlistine.midair.core.domain.util.Result
import com.kotlistine.midair.core.transfer.domain.LinkGeometry
import com.kotlistine.midair.core.transfer.domain.LinkState
import com.kotlistine.midair.core.transfer.domain.MediaChannel
import com.kotlistine.midair.core.transfer.domain.PeerLink
import com.kotlistine.midair.core.transfer.domain.SlideLink
import com.kotlistine.midair.core.transfer.domain.SlideMessage
import com.kotlistine.midair.core.transfer.domain.SlideMessage.FileRoute.Route
import com.kotlistine.midair.core.transfer.domain.TransferProgress
import com.kotlistine.midair.core.transfer.domain.Transport
import com.kotlistine.midair.feature.shelf.data.file.ReceivedFileStore
import com.kotlistine.midair.feature.shelf.domain.Delivery
import com.kotlistine.midair.feature.shelf.domain.IncomingFile
import com.kotlistine.midair.feature.shelf.domain.OutgoingFile
import com.kotlistine.midair.feature.shelf.domain.ShelfRepository
import com.kotlistine.midair.feature.shelf.domain.SlidePeer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How long a let-go card waits to hear which incoming file is its own (or that it is queued). */
private val OfferWait = 15.seconds

/**
 * How long a sender waits to hear whether the peer takes the file from its video itself. The answer leaves as soon as
 * the commit lands; a peer that never answers doesn't know how, and gets the file sent.
 */
private val RouteWait = 5.seconds

/** How often a sender looks at how much of a pulled file the peer has taken, for its progress. */
private val ServedPoll = 500.milliseconds

/** A stop for a file that wasn't being received yet is honoured for this long (ids come back once an item is gone). */
private val CancelKeep = 30.seconds

/**
 * How long a sender waits, once its bytes are through, to hear that the peer kept the file. Saving a big video to
 * Downloads takes a while; a peer that never says so may not have kept it, and the item comes back here (at worst a
 * copy on both phones, never a file on neither).
 */
private val KeptWait = 3.minutes

/**
 * The paired device as the shelf sees it, on a [PeerLink] (Nearby on Android). A handed-over card's file follows
 * it: the sender announces the transfer ([SlideMessage.FileOffer]) and streams the bytes; the receiver matches the
 * transfer to the card, waits for every byte, keeps the file and puts it on its shelf.
 *
 * Files go [maxActiveSends] at a time (one by default), like any file-sharing app: several files at once over one
 * radio each go slower, none finishes early, the receiver's storage takes several writes at once, and every card
 * message waits behind a chunk of each. A file waiting its turn is announced as queued ([SlideMessage.FileQueued]), so
 * the receiver keeps waiting for it. Either side can stop one file ([SlideMessage.FileCancelled]), queued or moving.
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
    private val scope: CoroutineScope,
    maxActiveSends: Int = 1,
) : SlidePeer {

    override val transport: Transport get() = peerLink.transport

    /** The defaults (any edge leads to the peer, an 8 mm gap) until the screens are lined up. */
    override val link: SlideLink get() = geometry.link.value

    override val isLinked: Flow<Boolean> = peerLink.state.map { it is LinkState.Connected }.distinctUntilChanged()

    override val media: MediaChannel? get() = peerLink.media

    // region Sending

    /** Turns to send: a file holds one while its bytes move. */
    private val sendSlots = Semaphore(maxActiveSends)

    /** This device's items whose files are queued or moving, with their transfer id once announced. */
    private val outgoing = MutableStateFlow<Map<String, Long?>>(emptyMap())

    /** Items whose send was stopped (here, or by the peer); their send fails as cancelled. */
    private val stoppedSends = MutableStateFlow<Set<String>>(emptySet())

    /** The peer's latest word on how the file of each of this device's items goes, by item id. */
    private val routes = MutableStateFlow<Map<String, RouteNote>>(emptyMap())

    /** This device's items whose sent files the peer said it kept ([SlideMessage.FileKept]). */
    private val keptByPeer = MutableStateFlow<Set<String>>(emptySet())

    // endregion

    // region Receiving

    /** Transfer ids announced by the peer, by the item id on its shelf. */
    private val offers = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The peer's items whose files wait for others to go first. */
    private val queued = MutableStateFlow<Set<String>>(emptySet())

    /** The peer's items whose files won't come (stopped on either side), and when that was heard. */
    private val cancelled = MutableStateFlow<Map<String, TimeSource.Monotonic.ValueTimeMark>>(emptyMap())

    /** Received files are written to their final place one at a time: storage is slow enough with one writer. */
    private val saving = Mutex()

    /** Files pulled from the peer go one at a time too, for the same reasons as sent ones. */
    private val pullSlots = Semaphore(1)

    // endregion

    init {
        peerLink.transport.incoming
            .onEach { message ->
                when (message) {
                    is SlideMessage.FileOffer -> {
                        offers.update { it + (message.itemId to message.transferId) }
                        queued.update { it - message.itemId }
                    }
                    is SlideMessage.FileQueued -> queued.update { it + message.itemId }
                    is SlideMessage.FileCancelled ->
                        if (message.bySender) {
                            // The peer stopped sending one of its items to this device.
                            markCancelled(message.itemId)
                            offers.value[message.itemId]?.let(peerLink::cancelTransfer)
                        } else {
                            // The peer stopped receiving one of this device's items.
                            stopSend(message.itemId)
                        }
                    is SlideMessage.FileRoute -> routes.update { it + (message.itemId to RouteNote(message.route)) }
                    // Only while its send is on: a word for an item no longer sent is old.
                    is SlideMessage.FileKept ->
                        if (message.itemId in outgoing.value) keptByPeer.update { it + message.itemId }
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    override suspend fun send(file: OutgoingFile, progress: TransferProgress): EmptyResult<DataError> {
        val id = file.itemId
        stoppedSends.update { it - id }
        keptByPeer.update { it - id }
        outgoing.update { it + (id to null) }
        // On Wi-Fi the peer pulls the file in pieces, holding back while a card is in the air (see Delivery.PULL).
        val media = peerLink.media
        val offersPull = !file.peerMayFetch && media != null && media.isFast
        if (offersPull) media.share(id, file.uri)
        try {
            if (file.peerMayFetch || offersPull) {
                when (peerFetch(id, progress)) {
                    Route.FETCHED -> return Result.Success(Unit)
                    Route.CANCELLED -> return Result.Failure(DataError.Network.CANCELLED)
                    null -> return Result.Failure(DataError.Network.DISCONNECTED)
                    Route.SEND, Route.FETCHING -> Unit
                }
            }
            unlessSendStopped(id) { takeTurn(id, progress) } ?: return Result.Failure(DataError.Network.CANCELLED)
            val sent = try {
                if (id in stoppedSends.value) return Result.Failure(DataError.Network.CANCELLED)
                peerLink.sendFile(
                    uri = file.uri,
                    announce = { transferId ->
                        outgoing.update { it + (id to transferId) }
                        // Stopped just before its id was known: stop it now (the link then sends nothing).
                        if (id in stoppedSends.value) peerLink.cancelTransfer(transferId)
                        peerLink.transport.send(SlideMessage.FileOffer(id, transferId))
                    },
                    onProgress = progress::progressed,
                )
            } finally {
                // The next file may go while the peer saves this one.
                sendSlots.release()
            }
            if (sent is Result.Failure) return sent
            return awaitKept(id, progress)
        } finally {
            if (offersPull) media.unshare(id)
            outgoing.update { it - id }
            stoppedSends.update { it - id }
            keptByPeer.update { it - id }
        }
    }

    /**
     * Waits, once all of [id]'s bytes went, for the peer to say it kept the file: they may have reached the peer
     * without anything there taking them in. Fails when the peer couldn't keep it, the link goes, or it never says.
     */
    private suspend fun awaitKept(id: String, progress: TransferProgress): EmptyResult<DataError> {
        progress.saving()
        val heard = combine(keptByPeer, stoppedSends, peerLink.state) { kept, stopped, link ->
            when {
                id in kept -> Result.Success(Unit)
                // The peer couldn't keep it (it says so as a stop), or this user stopped it meanwhile.
                id in stopped -> Result.Failure(DataError.Network.CANCELLED)
                link !is LinkState.Connected -> Result.Failure(DataError.Network.DISCONNECTED)
                else -> null
            }
        }
        return withTimeoutOrNull(KeptWait) { heard.filterNotNull().first() }
            ?: Result.Failure(DataError.Network.DISCONNECTED)
    }

    /** Waits for a turn to send; a file that has to wait tells the peer it is queued, so the peer keeps waiting. */
    private suspend fun takeTurn(id: String, progress: TransferProgress) {
        if (sendSlots.tryAcquire()) return
        progress.queued()
        peerLink.transport.send(SlideMessage.FileQueued(id))
        sendSlots.acquire()
    }

    override fun cancelSend(itemId: String) {
        if (itemId !in outgoing.value) return
        stopSend(itemId)
        // The peer drops its card for it (and stops fetching it, when it was taking it from the video).
        scope.launch { peerLink.transport.send(SlideMessage.FileCancelled(itemId, bySender = true)) }
    }

    private fun stopSend(itemId: String) {
        if (itemId !in outgoing.value) return
        stoppedSends.update { it + itemId }
        outgoing.value[itemId]?.let(peerLink::cancelTransfer)
    }

    /** Runs [block] unless the send of [itemId] is stopped first: null then. */
    private suspend fun <T : Any> unlessSendStopped(itemId: String, block: suspend () -> T): T? =
        unlessStopped(stoppedSends.map { itemId in it }, block)

    /**
     * Waits for the peer to say how the file of [itemId] goes, and while it fetches it, until it has. Returns how it
     * ended: [Route.SEND] when the peer wants it sent (or never answered), null when the link went meanwhile.
     */
    private suspend fun peerFetch(itemId: String, progress: TransferProgress): Route? = try {
        // Only fresh words count: one left over from an earlier item with the same id must not end this send.
        val heard = routes.map { notes -> notes[itemId]?.takeIf { it.at.elapsedNow() < RouteWait * 2 }?.route }
        val first = withTimeoutOrNull(RouteWait) { heard.filterNotNull().first() } ?: Route.SEND
        if (first != Route.FETCHING) {
            first
        } else {
            coroutineScope {
                // The peer may wait for its other files first; this side only sees what it has taken so far.
                progress.queued()
                val reporting = launch { reportServed(itemId, progress) }
                try {
                    merge(
                        heard.filter { it != null && it != Route.FETCHING },
                        peerLink.state.filter { it !is LinkState.Connected }.map { null },
                    ).first()
                } finally {
                    reporting.cancel()
                }
            }
        }
    } finally {
        routes.update { it - itemId }
    }

    /** Reports how much of [itemId] the peer has pulled, while it pulls. */
    private suspend fun reportServed(itemId: String, progress: TransferProgress) {
        val media = peerLink.media ?: return
        var last = 0L
        while (true) {
            delay(ServedPoll)
            val served = media.servedBytes(itemId)
            if (served > last) {
                last = served
                progress.progressed(served)
            }
        }
    }

    override suspend fun receive(file: IncomingFile, progress: TransferProgress): Result<String, DataError> {
        val id = file.peerItemId
        // A stop heard long ago was for an earlier item with this id.
        cancelled.update { all -> all.filterValues { it.elapsedNow() < CancelKeep } }
        try {
            when (file.delivery) {
                Delivery.SENT -> Unit
                Delivery.ASK_TO_SEND -> peerLink.transport.send(SlideMessage.FileRoute(id, Route.SEND))
                Delivery.FETCH -> fetch(file, progress)?.let { return it }
                Delivery.PULL -> {
                    // Anything kept under this id is from an earlier file (ids come back once an item is gone).
                    peerLink.media?.forget(id)
                    fetch(file, progress, oneAtATime = true)?.let { return it }
                }
            }
            val transferId = when (val offer = awaitOffer(id, progress)) {
                is Offer.Made -> offer.transferId
                Offer.Stopped -> {
                    // Its bytes may be on their way already: they stop here too.
                    offers.value[id]?.let(peerLink::cancelTransfer)
                    return Result.Failure(DataError.Network.CANCELLED)
                }
                Offer.Lost -> return Result.Failure(DataError.Network.DISCONNECTED)
                Offer.Queued -> error("Not an outcome")
            }
            if (id in cancelled.value) {
                peerLink.cancelTransfer(transferId)
                return Result.Failure(DataError.Network.CANCELLED)
            }
            val arrivedAt = when (val arrived = peerLink.receiveFile(transferId, progress::progressed)) {
                is Result.Failure -> return arrived
                is Result.Success -> arrived.data
            }
            val kept = keep(file, arrivedAt, progress)
            // The sender keeps its item until it hears this: a file not kept here goes back on its shelf.
            peerLink.transport.send(
                when (kept) {
                    is Result.Success -> SlideMessage.FileKept(id)
                    is Result.Failure -> SlideMessage.FileCancelled(id, bySender = false)
                },
            )
            return kept
        } finally {
            offers.update { it - id }
            queued.update { it - id }
            cancelled.update { it - id }
        }
    }

    /**
     * Waits for the peer to announce the file of [id]: within [OfferWait], or for as long as the peer says it is
     * queued. Ends early when the file is stopped or the link goes.
     */
    private suspend fun awaitOffer(id: String, progress: TransferProgress): Offer {
        val heard = combine(offers, queued, cancelled, peerLink.state) { offers, queued, cancelled, link ->
            val transferId = offers[id]
            when {
                id in cancelled -> Offer.Stopped
                transferId != null -> Offer.Made(transferId)
                link !is LinkState.Connected -> Offer.Lost
                id in queued -> Offer.Queued
                else -> null
            }
        }
        val first = withTimeoutOrNull(OfferWait) { heard.filterNotNull().first() } ?: return Offer.Lost
        if (first != Offer.Queued) return first
        progress.queued()
        return heard.filterNotNull().first { it != Offer.Queued }
    }

    /**
     * Takes the file from the peer's video over the media channel. Returns the outcome, or null when the fetch failed
     * with the link still up and the peer was asked to send the file instead.
     */
    private suspend fun fetch(
        file: IncomingFile,
        progress: TransferProgress,
        /** Waits for other pulled files to finish first. */
        oneAtATime: Boolean = false,
    ): Result<String, DataError>? {
        val media = peerLink.media
        if (media == null) {
            peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.SEND))
            return null
        }
        if (peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.FETCHING)) is Result.Failure) {
            return Result.Failure(DataError.Network.DISCONNECTED)
        }
        val fetched = unlessStopped(cancelled.map { file.peerItemId in it }) {
            if (oneAtATime && !pullSlots.tryAcquire()) {
                progress.queued()
                pullSlots.acquire()
            }
            try {
                media.fetch(file.peerItemId, file.sizeBytes) { fraction ->
                    progress.progressed((fraction.toDouble() * file.sizeBytes).toLong())
                }
            } finally {
                if (oneAtATime) pullSlots.release()
            }
        }
        if (fetched == null) {
            media.forget(file.peerItemId)
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
        val kept = keep(file, location, progress)
        if (kept is Result.Failure) {
            media.forget(file.peerItemId)
            peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.CANCELLED))
            return kept
        }
        // Only now may the peer let go of its file: this device has it for good.
        peerLink.transport.send(SlideMessage.FileRoute(file.peerItemId, Route.FETCHED))
        return kept
    }

    /**
     * Keeps the file that arrived at [location] and puts it on the shelf; returns its item id. A video read from the
     * peer while it played goes on from the kept copy.
     */
    private suspend fun keep(file: IncomingFile, location: String, progress: TransferProgress): Result<String, DataError> {
        progress.saving()
        val kept = when (val stored = saving.withLock { store.keep(location, file.name, file.mimeType) }) {
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
        markCancelled(peerItemId)
        offers.value[peerItemId]?.let(peerLink::cancelTransfer)
        // The peer takes it out of its queue (or stops it), and puts the item back on its shelf.
        scope.launch { peerLink.transport.send(SlideMessage.FileCancelled(peerItemId, bySender = false)) }
    }

    private fun markCancelled(peerItemId: String) {
        cancelled.update { it + (peerItemId to TimeSource.Monotonic.markNow()) }
    }

    /** What waiting for a file's announcement came to. */
    private sealed interface Offer {
        data class Made(val transferId: Long) : Offer
        data object Queued : Offer
        data object Stopped : Offer
        data object Lost : Offer
    }
}

/**
 * Runs [block] unless [isStopped] turns true first: null then. Only the block is stopped; the caller carries on (or is
 * cancelled itself, as usual).
 */
private suspend fun <T : Any> unlessStopped(isStopped: Flow<Boolean>, block: suspend () -> T): T? = coroutineScope {
    if (isStopped.first()) return@coroutineScope null
    val work = async { block() }
    val stopper = launch {
        isStopped.first { it }
        work.cancel()
    }
    try {
        work.await()
    } catch (e: CancellationException) {
        // The block was stopped, not this caller.
        if (!work.isCancelled || !isActive) throw e
        null
    } finally {
        stopper.cancel()
    }
}

/** A [Route] the peer sent, and when it arrived. */
private class RouteNote(val route: Route, val at: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow())
