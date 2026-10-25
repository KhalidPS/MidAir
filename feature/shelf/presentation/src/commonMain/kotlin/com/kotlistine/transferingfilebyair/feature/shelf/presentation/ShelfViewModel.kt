package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlistine.transferingfilebyair.core.domain.shelf.FileKind
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.domain.util.onFailure
import com.kotlistine.transferingfilebyair.core.media.VideoEngine
import com.kotlistine.transferingfilebyair.core.media.VideoEngineEvent
import com.kotlistine.transferingfilebyair.core.media.VideoSource
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
import com.kotlistine.transferingfilebyair.feature.shelf.domain.PlaybackCorrection
import com.kotlistine.transferingfilebyair.feature.shelf.domain.PlaybackFollower
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ArrivingItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.IncomingCardUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.PlayingVideoUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.toUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

/** How often a lifted card's video reports where it is, so the peer's half stays on the same frame. */
private const val PlaybackClockMillis = 250L

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
    /** Plays videos in their cards; the screen draws its picture. Without one, a tapped video opens in another app. */
    val videoEngine: VideoEngine? = null,
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
     * Names of the files being received. An item with one of these names appearing meanwhile is held back, so it
     * takes over from its arriving card in one step (other new items appear as usual).
     */
    private val receivingNames = mutableListOf<String>()

    /** Cards from the peer whose files are coming in, newest first. */
    private val arriving = mutableListOf<ArrivingItemUi>()

    /** Received item id → the id of the arriving card it took over from. */
    private val arrivedFrom = mutableMapOf<String, String>()

    private var outgoing: OutgoingSlide? = null
    private var incoming: IncomingSlide? = null

    /** The peer's video this device plays along with, while the peer's card is in the air. */
    private var following: FollowedVideo? = null
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
        videoEngine?.let(::watch)
    }

    override fun onCleared() {
        videoEngine?.release()
    }

    fun onAction(action: ShelfAction) {
        when (action) {
            ShelfAction.OnAddClick, ShelfAction.OnDevicesClick -> Unit
            is ShelfAction.OnFilesPicked -> addFiles(action.uris)
            is ShelfAction.OnOpenClick -> openItem(action.id)
            is ShelfAction.OnPreviewClick -> onPreviewClick(action.id)
            ShelfAction.OnScreenHidden -> videoEngine?.pause()
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
                if (arriving.any { it.id == action.id }) peer?.cancelReceive(action.id)
            is ShelfAction.OnRetrySend -> resend(action.id)
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
            val shown = if (receivingNames.isNotEmpty()) {
                // Hold back a received item until its id is known, so it never shows next to its arriving card.
                val published = state.items.mapTo(HashSet()) { it.id }
                visible.filter { it.id in published || it.name !in receivingNames }
            } else {
                visible
            }
            state.copy(
                items = shown,
                isLoading = false,
                arriving = arriving.toList(),
                arrivedFrom = arrivedFrom.toMap(),
            )
        }
        // Sent, removed or cleared away while playing.
        val playing = _state.value.playing
        if (playing != null && !isOnScreen(playing.itemId)) stopVideo()
    }

    /**
     * A card with [id] is on this screen: on the shelf, waiting for its file, or coming in from the peer. A received
     * item counts from the moment it is known, before the shelf's own list catches up with it.
     */
    private fun isOnScreen(id: String): Boolean =
        _state.value.items.any { it.id == id } ||
            arriving.any { it.id == id } ||
            incoming?.start?.itemId == id ||
            (id in arrivedFrom && shelfItems.none { it.id == id })

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

    // region A video playing in its card.

    private fun onPreviewClick(id: String) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        val engine = videoEngine
        if (item.kind != FileKind.VIDEO || engine == null) return openItem(id)
        val playing = _state.value.playing
        when {
            playing?.itemId != id -> {
                // Another video playing stops: the engine plays one at a time.
                engine.load(VideoSource.Local(item.uri))
                _state.update { it.copy(playing = PlayingVideoUi(id, isPaused = !engine.playWhenReady.value)) }
            }
            playing.isPaused -> engine.play()
            else -> engine.pause()
        }
    }

    private fun watch(engine: VideoEngine) {
        // The engine can pause by itself (audio focus lost, headphones unplugged), so the card follows it.
        engine.playWhenReady
            .onEach { playWhenReady ->
                _state.update { it.copy(playing = it.playing?.copy(isPaused = !playWhenReady)) }
            }
            .launchIn(viewModelScope)
        engine.events
            .onEach { event ->
                when (event) {
                    VideoEngineEvent.Ended -> stopVideo()
                    is VideoEngineEvent.Failed -> {
                        stopVideo()
                        eventChannel.send(ShelfEvent.ShowError(event.error))
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /** The card goes back to its still picture, and the decoder is freed. */
    private fun stopVideo() {
        following?.hold?.cancel()
        following = null
        if (_state.value.playing == null) return
        videoEngine?.release()
        _state.update { it.copy(playing = null) }
    }

    // endregion

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
        val sharesVideo = shareVideo(peer, item, slide)
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
                // Right behind the start, with the position read as it goes.
                if (sharesVideo && !slide.startFailed) {
                    playbackReport(item.id, offer = true, httpUrl = slide.videoUrl)?.let { peer.transport.send(it) }
                }
            }
            if (sharesVideo) reportPlayback(peer, slide)
        }
    }

    /**
     * When the lifted card is the video playing here, lets the peer read its file, so the peer's half of the card plays
     * it too: over the Wi-Fi network both phones are on, or over the pairing link once it is fast enough.
     */
    private fun shareVideo(peer: SlidePeer, item: ShelfItemUi, slide: OutgoingSlide): Boolean {
        if (videoEngine == null || _state.value.playing?.itemId != item.id) return false
        val media = peer.media ?: return false
        val url = media.share(item.id, item.uri)
        if (url == null && !media.isFast) {
            media.unshare(item.id)
            return false
        }
        slide.videoUrl = url
        return true
    }

    /** Where the video of [id] is now, as an offer (with the lift) or a clock report; null once it stopped. */
    private fun playbackReport(id: String, offer: Boolean, httpUrl: String? = null): SlideMessage? {
        val engine = videoEngine ?: return null
        val playing = _state.value.playing?.takeIf { it.itemId == id } ?: return null
        val item = shelfItems.firstOrNull { it.id == id } ?: return null
        return if (offer) {
            SlideMessage.PlaybackOffer(id, item.sizeBytes, engine.positionMs, isPlaying = !playing.isPaused, httpUrl)
        } else {
            SlideMessage.PlaybackClock(id, engine.positionMs, isPlaying = !playing.isPaused)
        }
    }

    /** Reports where the video is a few times a second, for as long as its card is in the air. */
    private suspend fun reportPlayback(peer: SlidePeer, slide: OutgoingSlide) {
        while (true) {
            delay(PlaybackClockMillis)
            if (outgoing !== slide) return
            val sent = sendLock.withLock {
                val report = playbackReport(slide.id, offer = false) ?: return@withLock false
                peer.transport.send(report)
                true
            }
            if (!sent) return
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
        // Back on this shelf: the peer stops reading its video. (A sent card's video stays readable: the peer plays on.)
        peer?.media?.unshare(id)
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

    // region Receiving side: show the peer's card arriving, then keep it in its slot while its file comes in.

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
            // Answered here for the whole app (the shelf is always under the other screens), with this clock's time
            // so the line-up can compare the two phones' clocks.
            is SlideMessage.Ping -> post(SlideMessage.Pong(message.sentAtMillis, clock.now().toEpochMilliseconds()))
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
                if (following?.itemId == message.itemId || _state.value.playing?.itemId == message.itemId) stopVideo()
                eventChannel.send(ShelfEvent.PeerCardWithdrawn(message.itemId))
            }
            is SlideMessage.PlaybackOffer -> followPeerVideo(peer, message)
            is SlideMessage.PlaybackClock ->
                following?.takeIf { it.itemId == message.itemId }?.let { onPeerClock(it, message) }
            // Matched to its card by the peer link, which is waiting for the file; line-up swipes are pairing's.
            is SlideMessage.FileOffer, is SlideMessage.SwipeCrossed -> Unit
            is SlideMessage.DragCommit -> {
                val slide = incoming?.takeIf { it.start.itemId == message.itemId } ?: return
                incoming = null
                val start = slide.start
                // Its slot opens at the top of the shelf at once; the card flies into it and waits there for its file.
                arriving.removeAll { it.id == start.itemId }
                arriving.add(0, ArrivingItemUi(start.itemId, start.name, aspectRatio = start.aspectRatio()))
                publishItems()
                takeOverPeerVideo(start.itemId)
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
        receivingNames += start.name
        var lastPercent = -1
        val result = peer.receive(IncomingFile(start.itemId, start.name, start.mimeType, start.sizeBytes)) { fraction ->
            // Whole percents are plenty for a ring, and keep the state light on big files.
            val percent = (fraction * 100).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                // Back on the transfer scope's thread, whichever thread the link reports from.
                transferScope.launch { showProgress(start.itemId, fraction) }
            }
        }
        receivingNames.remove(start.name)
        arriving.removeAll { it.id == start.itemId }
        when (result) {
            is Result.Success -> {
                // The item takes the arriving card's place in the same update, and plays on if its card did.
                arrivedFrom[result.data] = start.itemId
                val playing = _state.value.playing
                if (playing?.itemId == start.itemId) _state.update { it.copy(playing = playing.copy(itemId = result.data)) }
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

    private fun showProgress(id: String, fraction: Float) {
        val index = arriving.indexOfFirst { it.id == id }
        if (index < 0) return
        arriving[index] = arriving[index].copy(progress = fraction.coerceIn(0f, 1f))
        publishItems()
    }

    /**
     * The peer's lifted card is its playing video: play it here too, read from the peer as it plays, from the frame
     * the peer is at, muted until the card is let go here.
     */
    private fun followPeerVideo(peer: SlidePeer, offer: SlideMessage.PlaybackOffer) {
        val engine = videoEngine ?: return
        val media = peer.media ?: return
        if (incoming?.start?.itemId != offer.itemId) return
        val follower = PlaybackFollower()
        follower.report(offer.positionMs, offer.isPlaying, receivedAtMs = nowMillis(), oneWayMs = oneWayMillis())
        val video = FollowedVideo(offer.itemId, follower)
        following = video
        viewModelScope.launch {
            // Over the pairing link once it is on Wi-Fi Direct: tried on phones, the shared Wi-Fi was far slower then
            // (the one radio hops between the two networks, and the router carries every byte twice). The shared
            // Wi-Fi is for while the pairing link is still on Bluetooth.
            val url = if (media.isFast) null else offer.httpUrl?.takeIf { media.canReach(it) }
            if (following !== video) return@launch
            val source = when {
                media.isFast -> VideoSource.Remote(offer.itemId, offer.sizeBytes) { media.open(offer.itemId, it) }
                url != null -> VideoSource.Url(url)
                else -> {
                    // Neither way can carry it: the card comes over with its picture, as any file.
                    following = null
                    return@launch
                }
            }
            val now = nowMillis()
            engine.load(source, startAtMs = follower.startAt(now) ?: offer.positionMs, playWhenReady = offer.isPlaying)
            // The peer's speaker plays it while the card is over there.
            engine.setVolume(0f)
            video.isLoaded = true
            _state.update { it.copy(playing = PlayingVideoUi(offer.itemId, isPaused = !offer.isPlaying)) }
        }
    }

    /** Keeps this half of the card on the frame the peer's half shows. */
    private fun onPeerClock(video: FollowedVideo, clock: SlideMessage.PlaybackClock) {
        val engine = videoEngine ?: return
        val now = nowMillis()
        video.follower.report(clock.positionMs, clock.isPlaying, receivedAtMs = now, oneWayMs = oneWayMillis())
        // Still finding out which way to read it: nothing to steer yet.
        if (!video.isLoaded) return
        // Holding a frame until the peer gets there: it plays on by itself when the hold ends.
        if (video.hold?.isActive == true) return
        if (clock.isPlaying != engine.playWhenReady.value) {
            if (clock.isPlaying) engine.play() else engine.pause()
        }
        // While it buffers its position stands still, which says nothing about how far off it is.
        if (clock.isPlaying && !engine.isPlaying) return
        if (clock.isPlaying) video.follower.onPlaying(now)
        when (val correction = video.follower.correct(engine.positionMs, now)) {
            is PlaybackCorrection.Seek -> engine.seekTo(correction.positionMs)
            is PlaybackCorrection.Speed -> engine.setSpeed(correction.speed)
            is PlaybackCorrection.Hold -> {
                engine.setSpeed(1f)
                engine.pause()
                video.hold = viewModelScope.launch {
                    delay(correction.durationMs)
                    if (following === video) engine.play()
                }
            }
            null -> Unit
        }
    }

    /** The peer let its card go here: this phone plays the video on, with sound; the peer's has stopped. */
    private fun takeOverPeerVideo(id: String) {
        val video = following?.takeIf { it.itemId == id } ?: return
        following = null
        video.hold?.cancel()
        // Let go before it was loaded: it never will be (the card comes over with its picture).
        if (!video.isLoaded) return
        videoEngine?.apply {
            setSpeed(1f)
            setVolume(1f)
            // Cut short a hold: it plays on from the frame it waited on.
            if (video.follower.isPlaying) play()
        }
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private fun oneWayMillis(): Long = (roundTripMillis / 2f).toLong()

    private fun SlideMessage.DragStart.aspectRatio(): Float =
        if (cardHeightMm > 0f) (cardWidthMm / cardHeightMm).coerceIn(0.5f, 2f) else 1f

    // endregion

    private class OutgoingSlide(val id: String) {
        var seq = 0
        var isStreaming = false
        var startFailed = false
        var edge: ExitEdge? = null
        var outwardMmPerS = 0f
        var alongEdgeMmPerS = 0f

        /** Where the peer reads this card's video over the shared Wi-Fi, when it is one. */
        var videoUrl: String? = null
    }

    private class IncomingSlide(val start: SlideMessage.DragStart) {
        var lastSeq = -1
    }

    private class FollowedVideo(val itemId: String, val follower: PlaybackFollower) {
        /** Loaded into the engine: until then (finding out which way to read it) nothing is steered. */
        var isLoaded = false

        /** Waiting on a frame for the peer to get there. */
        var hold: Job? = null
    }
}
