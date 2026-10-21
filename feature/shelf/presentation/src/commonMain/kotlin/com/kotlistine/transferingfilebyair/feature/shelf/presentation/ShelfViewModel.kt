package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.domain.util.onFailure
import com.kotlistine.transferingfilebyair.core.transfer.domain.ApproachRangeMm
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.transferingfilebyair.core.transfer.domain.keeping
import com.kotlistine.transferingfilebyair.core.transfer.domain.arrival
import com.kotlistine.transferingfilebyair.core.transfer.domain.incomingCardPosition
import com.kotlistine.transferingfilebyair.core.transfer.domain.predictedAhead
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.IncomingCardUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.toUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** A card sent again after a failure has no slide to measure; the peer draws it this wide. */
private const val RetryCardWidthMm = 40f

/** How long the incoming card's on-screen smoothing trails its target; prediction runs this much further ahead. */
private const val SmoothingLeadSeconds = 0.03f

class ShelfViewModel(
    private val repository: ShelfRepository,
    private val density: ScreenDensity,
    /** The linked device, if any. Without one, slides only take items off this shelf. */
    private val peer: SlidePeer? = null,
    /**
     * Where file transfers run: an app-wide scope, so leaving the screen doesn't stop a file mid-way (this ViewModel's
     * own scope when not given). Must run on the main thread, like the rest of this class.
     */
    private val transfers: CoroutineScope? = null,
    /** Keeps the app alive while a file moves (a notification on Android). */
    private val keepAlive: TransferKeepAlive = TransferKeepAlive.None,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private var isObservingItems = false

    private val transferScope: CoroutineScope get() = transfers ?: viewModelScope

    /** Everything in the repository, including items that were just sent and are waiting out the undo window. */
    private var shelfItems: List<ShelfItemUi> = emptyList()

    /** Sent items, hidden from the shelf until they are removed for good (or brought back). */
    private val sentIds = mutableSetOf<String>()
    private var isClearing = false

    /**
     * The name of a file being received. An item with that name appearing meanwhile is held back, so it shows up
     * together with [landingItemId] (other new items appear as usual).
     */
    private var receivingName: String? = null

    /** The peer's item whose file is coming in, which a tap on the arriving card can stop. */
    private var receivingPeerItemId: String? = null
    private var landingItemId: String? = null

    private var outgoing: OutgoingSlide? = null
    private var incoming: IncomingSlide? = null
    private var viewportWidthPx = 0
    private var roundTripMillis = 0f

    /** Messages to the peer go out one at a time, in order, whatever the transport's own threading. */
    private val sendLock = Mutex()

    private val _state = MutableStateFlow(ShelfState())
    val state: StateFlow<ShelfState> = _state
        .onStart {
            if (!isObservingItems) {
                isObservingItems = true
                observeItems()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ShelfState())

    // Buffered: incoming-card moves arrive at frame rate and must not wait on a collector that is mid-event.
    private val eventChannel = Channel<ShelfEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        if (peer != null) talkTo(peer)
    }

    fun onAction(action: ShelfAction) {
        when (action) {
            ShelfAction.OnAddClick, ShelfAction.OnDevicesClick -> Unit
            is ShelfAction.OnFilesPicked -> addFiles(action.uris)
            is ShelfAction.OnOpenClick -> openItem(action.id)
            is ShelfAction.OnRemoveClick -> removeItem(action.id)
            is ShelfAction.OnItemSent -> sendItem(action.id, action.edge)
            is ShelfAction.OnUndoSend -> undoSend(action.id)
            is ShelfAction.OnUndoOfferClosed -> finishSend(action.id)
            ShelfAction.OnClearClick -> _state.update { it.copy(isClearConfirmationVisible = true) }
            ShelfAction.OnClearDismiss -> _state.update { it.copy(isClearConfirmationVisible = false) }
            ShelfAction.OnClearConfirm -> {
                _state.update { it.copy(isClearConfirmationVisible = false) }
                clearShelf()
            }
            is ShelfAction.OnSlideLifted -> startMirroring(action)
            is ShelfAction.OnSlideMoved -> mirrorMove(action.frame)
            is ShelfAction.OnSlideReturned -> stopMirroring(action.id)
            is ShelfAction.OnViewportChanged -> viewportWidthPx = action.widthPx
            is ShelfAction.OnIncomingCardTap ->
                if (action.id == receivingPeerItemId) peer?.cancelReceive(action.id)
            is ShelfAction.OnRetrySend -> resend(action.id)
            ShelfAction.OnLandingFinished -> {
                landingItemId = null
                publishItems()
            }
        }
    }

    private fun observeItems() {
        repository.observeItems()
            .onEach { items ->
                val now = clock.now()
                shelfItems = items.map { it.toUi(now, timeZone) }
                publishItems()
            }
            .launchIn(viewModelScope)
    }

    private fun publishItems() {
        _state.update { state ->
            val visible = shelfItems.filterNot { it.id in sentIds }
            val holdBack = receivingName
            val shown = if (holdBack != null) {
                // Hold back the arriving item until its id is known, so it never shows without its landing.
                val published = state.items.mapTo(HashSet()) { it.id }
                visible.filter { it.id in published || it.name != holdBack }
            } else {
                visible
            }
            state.copy(items = shown, isLoading = false, landingItemId = landingItemId)
        }
    }

    private fun addFiles(uris: List<String>) {
        viewModelScope.launch {
            val failures = uris.mapNotNull { uri -> (repository.addFile(uri) as? Result.Failure)?.error }
            if (failures.isNotEmpty()) {
                eventChannel.send(ShelfEvent.ShowAddFailed(failedCount = failures.size, error = failures.first()))
            }
        }
    }

    private fun openItem(id: String) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            eventChannel.send(ShelfEvent.OpenFile(uri = item.uri, mimeType = item.mimeType))
        }
    }

    private fun removeItem(id: String) {
        viewModelScope.launch {
            repository.removeItem(id).onFailure { eventChannel.send(ShelfEvent.ShowError(it)) }
        }
    }

    /**
     * The item leaves the shelf at once (the grid closes the gap). When the peer got it, the send is final.
     * Otherwise the UI offers to undo it, and it is only removed from the repository once that offer closes
     * ([ShelfAction.OnUndoOfferClosed]), however long the offer stays up: a slip, or a failed link, loses nothing.
     */
    private fun sendItem(id: String, edge: ExitEdge?) {
        val item = shelfItems.firstOrNull { it.id == id } ?: return
        if (!sentIds.add(id)) return
        publishItems()
        val slide = outgoing?.takeIf { it.id == id }
        outgoing = null
        transferScope.launch {
            if (!handOver(slide, edge)) {
                eventChannel.send(ShelfEvent.ShowSent(id = id, name = item.name))
                return@launch
            }
            keepAlive.keeping("Sending “${item.name}”") { deliverFile(item) }
        }
    }

    /** The file follows the card; the item leaves this shelf for good only once the peer has all of it. */
    private suspend fun deliverFile(item: ShelfItemUi) {
        val delivered = peer?.send(OutgoingFile(itemId = item.id, uri = item.uri))
        if (delivered is Result.Failure) {
            undoSend(item.id)
            eventChannel.send(ShelfEvent.ShowSendFailed(id = item.id, name = item.name, error = delivered.error))
        } else {
            finishSend(item.id)
        }
    }

    /**
     * Sends a file again after a failed transfer, without a slide: the peer is told about the card and that it was let
     * go, so it plays the arrival and takes the file as usual.
     */
    private fun resend(id: String) {
        val peer = peer ?: return
        val item = shelfItems.firstOrNull { it.id == id } ?: return
        if (!sentIds.add(id)) return
        publishItems()
        transferScope.launch {
            // Sent in this coroutine, not queued: the peer must know the card before it is told it was let go.
            deliver(
                peer,
                SlideMessage.DragStart(
                    itemId = item.id,
                    name = item.name,
                    mimeType = item.mimeType,
                    sizeBytes = item.sizeBytes,
                    cardWidthMm = RetryCardWidthMm,
                    cardHeightMm = RetryCardWidthMm / item.aspectRatio,
                    thumbnail = null,
                ),
            )
            val committed = deliver(peer, SlideMessage.DragCommit(item.id, outwardMmPerS = 0f, alongEdgeMmPerS = 0f))
            if (committed is Result.Failure) {
                undoSend(id)
                eventChannel.send(ShelfEvent.ShowSendFailed(id = item.id, name = item.name, error = committed.error))
            } else {
                keepAlive.keeping("Sending “${item.name}”") { deliverFile(item) }
            }
        }
    }

    private fun undoSend(id: String) {
        if (sentIds.remove(id)) publishItems()
    }

    private fun finishSend(id: String) {
        // A clear in flight takes sent items with it; removing them as well would race it.
        if (id !in sentIds || isClearing) return
        transferScope.launch {
            val removal = repository.removeItem(id)
            sentIds.remove(id)
            removal.onFailure { error ->
                publishItems()
                eventChannel.send(ShelfEvent.ShowError(error))
            }
        }
    }

    private fun clearShelf() {
        // Sent items go with everything else; they stay hidden until the clear lands.
        isClearing = true
        viewModelScope.launch {
            val cleared = repository.clear()
            isClearing = false
            sentIds.clear()
            cleared.onFailure {
                publishItems()
                eventChannel.send(ShelfEvent.ShowError(it))
            }
        }
    }

    // region Sending side: mirror the carried card to the peer.

    private fun startMirroring(action: ShelfAction.OnSlideLifted) {
        if (peer == null) return
        val item = shelfItems.firstOrNull { it.id == action.id } ?: return
        val slide = OutgoingSlide(action.id)
        outgoing = slide
        val start = SlideMessage.DragStart(
            itemId = item.id,
            name = item.name,
            mimeType = item.mimeType,
            sizeBytes = item.sizeBytes,
            cardWidthMm = density.xPxToMm(action.cardWidthPx),
            cardHeightMm = density.yPxToMm(action.cardHeightPx),
            thumbnail = action.thumbnail,
        )
        val peer = peer
        viewModelScope.launch {
            // One turn of the send lock: the retry goes out before anything queued after the start, and the outcome
            // is recorded before a commit can be sent.
            sendLock.withLock {
                var result = peer.transport.send(start)
                if (result is Result.Failure && result.error == DataError.Network.PAYLOAD_TOO_LARGE && start.thumbnail != null) {
                    // The picture made it too big for one message: the peer can draw the card without it.
                    result = peer.transport.send(start.copy(thumbnail = null))
                }
                // Without a start the peer ignores the rest of the slide, so the card can't be handed over.
                if (result is Result.Failure) slide.startFailed = true
            }
        }
    }

    /**
     * Moves stream while the card is within [ApproachRangeMm] of the edge facing the peer. When the peer's side is
     * unknown, the edge the card first nears is kept for the whole stream, so the peer never sees it jump sides.
     */
    private fun mirrorMove(frame: SlideFrame) {
        val peer = peer ?: return
        val slide = outgoing?.takeIf { it.id == frame.id } ?: return
        val edge = peer.link.peerSide ?: slide.edge?.takeIf { slide.isStreaming } ?: frame.nearestEdge
        val penetrationMm = density.xPxToMm(frame.penetrationPx(edge))
        val near = penetrationMm >= -ApproachRangeMm
        // One more move goes out after the card leaves the range, so the peer sees it go.
        if (!near && !slide.isStreaming) return
        slide.isStreaming = near
        slide.seq++
        slide.edge = edge
        slide.outwardMmPerS = density.xPxToMm(frame.outwardPxPerS(edge))
        slide.alongEdgeMmPerS = density.yPxToMm(frame.velocityYPxPerS)
        post(
            SlideMessage.DragMove(
                itemId = frame.id,
                seq = slide.seq,
                exitEdge = edge,
                alongEdgeMm = density.yPxToMm(frame.cardTopPx),
                penetrationMm = penetrationMm,
                outwardMmPerS = slide.outwardMmPerS,
                alongEdgeMmPerS = slide.alongEdgeMmPerS,
                tiltDegrees = frame.tiltDegrees,
            ),
        )
    }

    private fun stopMirroring(id: String) {
        if (outgoing?.id != id) return
        outgoing = null
        post(SlideMessage.DragCancel(id))
    }

    /**
     * Hands a sent card over to the peer. It counts only if the card left through the edge it was streamed to, that
     * edge faces the peer, and both its start and the commit actually went out; otherwise the peer withdraws it.
     */
    private suspend fun handOver(slide: OutgoingSlide?, exitEdge: ExitEdge?): Boolean {
        val peer = peer ?: return false
        if (slide == null) return false
        val edge = slide.edge
        if (edge == null || edge != exitEdge || !peer.link.leadsToPeer(edge)) {
            post(SlideMessage.DragCancel(slide.id))
            return false
        }
        val committed = deliver(peer, SlideMessage.DragCommit(slide.id, slide.outwardMmPerS, slide.alongEdgeMmPerS))
        if (committed is Result.Success && !slide.startFailed) return true
        post(SlideMessage.DragCancel(slide.id))
        return false
    }

    /** Queues [message] for the peer. Messages go out in call order. */
    private fun post(message: SlideMessage) {
        val peer = peer ?: return
        transferScope.launch { deliver(peer, message) }
    }

    /** Sends [message] after everything posted before it (the lock is fair) and returns how it went. */
    private suspend fun deliver(peer: SlidePeer, message: SlideMessage): EmptyResult<DataError.Network> =
        sendLock.withLock { peer.transport.send(message) }

    // endregion

    // region Receiving side: show the peer's card arriving, then land it.

    private fun talkTo(peer: SlidePeer) {
        peer.isLinked
            .onEach { linked -> _state.update { it.copy(isPaired = linked) } }
            .launchIn(viewModelScope)
        peer.transport.incoming
            .onEach { onPeerMessage(peer, it) }
            .launchIn(viewModelScope)
    }

    private suspend fun onPeerMessage(peer: SlidePeer, message: SlideMessage) {
        when (message) {
            is SlideMessage.Ping -> post(SlideMessage.Pong(message.sentAtMillis))
            is SlideMessage.Pong -> {
                val sample = (clock.now().toEpochMilliseconds() - message.pingSentAtMillis).toFloat()
                roundTripMillis = if (roundTripMillis == 0f) sample else roundTripMillis * 0.8f + sample * 0.2f
            }
            is SlideMessage.DragStart -> {
                incoming = IncomingSlide(message)
                // Measure the link now: the answer is back long before the card reaches the edge.
                post(SlideMessage.Ping(clock.now().toEpochMilliseconds()))
                eventChannel.send(
                    ShelfEvent.PeerCardLifted(
                        IncomingCardUi(
                            id = message.itemId,
                            name = message.name,
                            widthPx = density.xMmToPx(message.cardWidthMm),
                            heightPx = density.yMmToPx(message.cardHeightMm),
                            thumbnail = message.thumbnail,
                        ),
                    ),
                )
            }
            is SlideMessage.DragMove -> {
                val slide = incoming?.takeIf { it.start.itemId == message.itemId && message.seq > it.lastSeq } ?: return
                slide.lastSeq = message.seq
                // Draw where the card is by now, not where it was when the message left the peer.
                val lead = roundTripMillis / 2f / 1000f + SmoothingLeadSeconds
                val position = incomingCardPosition(
                    move = message.predictedAhead(lead),
                    cardWidthMm = slide.start.cardWidthMm,
                    receiverWidthMm = density.xPxToMm(viewportWidthPx.toFloat()),
                    link = peer.link,
                )
                eventChannel.send(
                    ShelfEvent.PeerCardMoved(
                        id = message.itemId,
                        leftPx = density.xMmToPx(position.leftMm),
                        topPx = density.yMmToPx(position.topMm),
                        tiltDegrees = message.tiltDegrees,
                        arrival = message.arrival(peer.link),
                        entryEdge = message.exitEdge.opposite,
                    ),
                )
            }
            is SlideMessage.DragCancel -> {
                if (incoming?.start?.itemId != message.itemId) return
                incoming = null
                eventChannel.send(ShelfEvent.PeerCardWithdrawn(message.itemId))
            }
            // Matched to its card by the peer link, which is waiting for the file.
            is SlideMessage.FileOffer -> Unit
            is SlideMessage.DragCommit -> {
                val slide = incoming?.takeIf { it.start.itemId == message.itemId } ?: return
                incoming = null
                eventChannel.send(ShelfEvent.PeerCardArrived(message.itemId))
                // In its own coroutine: messages keep flowing while the file comes in, and leaving the screen
                // doesn't stop it.
                transferScope.launch {
                    keepAlive.keeping("Receiving “${slide.start.name}”") { receive(peer, slide.start) }
                }
            }
        }
    }

    private suspend fun receive(peer: SlidePeer, start: SlideMessage.DragStart) {
        receivingName = start.name
        receivingPeerItemId = start.itemId
        var lastPercent = -1
        val result = peer.receive(IncomingFile(start.itemId, start.name, start.mimeType, start.sizeBytes)) { fraction ->
            // Whole percents are plenty for a ring, and keep the event stream light on big files.
            val percent = (fraction * 100).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                eventChannel.trySend(ShelfEvent.PeerCardProgress(start.itemId, fraction))
            }
        }
        receivingName = null
        receivingPeerItemId = null
        when (result) {
            is Result.Success -> {
                landingItemId = result.data
                publishItems()
            }
            is Result.Failure -> {
                publishItems()
                eventChannel.send(ShelfEvent.PeerCardWithdrawn(start.itemId))
                // A transfer this user stopped needs no message.
                if (result.error != DataError.Network.CANCELLED) eventChannel.send(ShelfEvent.ShowError(result.error))
            }
        }
    }

    // endregion

    private class OutgoingSlide(val id: String) {
        var seq = 0
        var isStreaming = false
        var startFailed = false
        var edge: ExitEdge? = null
        var outwardMmPerS = 0f
        var alongEdgeMmPerS = 0f
    }

    private class IncomingSlide(val start: SlideMessage.DragStart) {
        var lastSeq = -1
    }
}
