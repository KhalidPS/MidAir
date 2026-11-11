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
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferBoard
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferDirection
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKey
import com.kotlistine.transferingfilebyair.core.transfer.domain.keeping
import com.kotlistine.transferingfilebyair.core.transfer.domain.arrival
import com.kotlistine.transferingfilebyair.core.transfer.domain.incomingCardPosition
import com.kotlistine.transferingfilebyair.core.transfer.domain.incomingCardVelocity
import com.kotlistine.transferingfilebyair.feature.shelf.domain.Delivery
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.PlaybackCorrection
import com.kotlistine.transferingfilebyair.feature.shelf.domain.PlaybackFollower
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SentItems
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import com.kotlistine.transferingfilebyair.feature.shelf.domain.UnattendedReceiver
import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.AirGesture
import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.AirGestureDetector
import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.HandFrame
import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.HandPose
import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.HandSmoother
import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.HandTracker
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.AirGestureUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ArrivingItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.CatchableCardUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.HandPointUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.IncomingCardUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.PlayingVideoUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.TransfersUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.toUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The error of a failed [Result]; null for a success; [whenMissing] when there is no result (it was cut short). */
private fun Result<*, DataError>?.errorOr(whenMissing: DataError): DataError? = when (this) {
    null -> whenMissing
    is Result.Failure -> error
    is Result.Success -> null
}

/** Moves kept for the next message at most: half a second of frames, plenty to draw the card's path evenly. */
private const val MaxBatchedMoves = 30

/** A card sent again after a failure has no slide to measure; the peer draws it this wide. */
private const val RetryCardWidthMm = 40f

/** How often a lifted (or grabbed) card's video reports where it is, so the peer's half stays on the same frame. */
private const val PlaybackClockMillis = 250L

/**
 * A card held in the air longer than this goes back to its slot: nobody is coming to take it. Long enough to walk the
 * hand over, short enough not to leave a card stuck (the hint offers putting it back sooner, with a tap).
 */
private val HoldLimit = 12.seconds

/**
 * A hand opening near this phone while it holds a card puts the card back only after this long: the phones lie side
 * by side, so this camera may well see the hand open over the other phone, whose catch then wins.
 */
private val PutBackGrace = 800.milliseconds

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
    /** Every file moving either way, app-wide (the notification shows it too). */
    private val board: TransferBoard = TransferBoard(),
    /**
     * Sent items, hidden from the shelf until they are removed for good (or brought back). App-wide: a send goes on
     * after its screen is closed, so the next screen must hide its item too.
     */
    private val sentIds: SentItems = SentItems(),
    /** Takes in the peer's files while no shelf screen is open; this screen takes them itself while it lives. */
    unattended: UnattendedReceiver? = null,
    /** Plays videos in their cards; the screen draws its picture. Without one, a tapped video opens in another app. */
    val videoEngine: VideoEngine? = null,
    /** Watches for a fist and an open hand in front of the screen (the front camera). Without one, no air gestures. */
    private val hands: HandTracker? = null,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private var isObservingItems = false

    /** Hands the peer's cards back to the unattended receiver once this screen is gone. */
    private val stopAttending: () -> Unit = unattended?.attend() ?: {}

    private val transferScope: CoroutineScope get() = transfers ?: viewModelScope

    /** Everything in the repository, including items that were just sent and are waiting out the undo window. */
    private var shelfItems: List<ShelfItemUi> = emptyList()

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

    /** The video the peer said is playing on it: its index is read ahead, for the moment its card is lifted. */
    private var hinted: SlideMessage.PlaybackHint? = null

    /** A received item whose video is still read from the peer: it moves to its own file once the shelf has it. */
    private var streamedFromPeer: String? = null
    private var viewportWidthPx = 0
    private var roundTripMillis = 0f

    /** Messages to the peer go out one at a time, in order, whatever the transport's own threading. */
    private val sendLock = Mutex()

    /**
     * Moves of the card in the air that haven't gone out yet, oldest first. Moves are made every frame, but while a
     * file or a video's pieces share the link, one message can take hundreds of milliseconds to go. One message per
     * move then piled up by the thousand (the peer played the backlog back in slow motion, and the let-go waited
     * behind all of it); dropping all but the newest left the peer too few points to draw the card's path evenly.
     * So the moves made while one message goes out all go together in the next ([SlideMessage.DragMoves]): every
     * point arrives, and never more than one message waits. At most [MaxBatchedMoves] are kept.
     */
    private val pendingMoves = ArrayDeque<SlideMessage.DragMove>()
    private var moveSender: Job? = null

    private var air = AirGestureUi(isAvailable = hands != null)
    private val detector = AirGestureDetector()
    private val smoother = HandSmoother()
    private var watchJob: Job? = null
    private var isScreenVisible = false
    private var isCameraPermitted = false

    /** The camera failed: it isn't tried again until the user turns air gestures on again or grants it anew. */
    private var cameraFailed = false

    /** Air gestures turn on once the camera permission being asked for is granted. */
    private var turnOnWhenPermitted = false

    /** The card in this user's hand, announced to the peer. */
    private var held: HeldCard? = null

    /** The last grab seen by the camera, waiting for its card to be picked ([ShelfAction.OnCardGrabbed]). */
    private var pendingGrab: AirGesture.Grab? = null

    /** The card in the peer's user's hand, catchable here (with its video, when it carries one). */
    private var catchable: IncomingSlide? = null

    /** Peer cards caught here whose files are still to come; the peer may still take one back. */
    private val caughtIds = mutableSetOf<String>()

    private val _hand = MutableStateFlow<HandPointUi?>(null)

    /** The hand in front of the camera while air gestures watch, at camera rate. Read it in the draw phase only. */
    val hand: StateFlow<HandPointUi?> = _hand

    private val _state = MutableStateFlow(ShelfState())
    val state: StateFlow<ShelfState> = _state
        .onStart {
            if (!isObservingItems) {
                isObservingItems = true
                observeItems()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ShelfState())

    /**
     * Every file moving to or from the peer, with its own progress. Apart from [state] on purpose: it changes a few
     * times a second per file, and only the transfers list and the arriving cards' labels need to follow it, not the
     * whole shelf.
     */
    val fileTransfers: StateFlow<TransfersUi> = board.transfers
        .map { list -> TransfersUi(list.map { it.toUi() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), TransfersUi())

    // Buffered: incoming-card moves arrive at frame rate and must not wait on a collector that is mid-event.
    private val eventChannel = Channel<ShelfEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        if (peer != null) talkTo(peer)
        videoEngine?.let(::watch)
        publishAir()
    }

    override fun onCleared() {
        stopAttending()
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
                if (arriving.any { it.id == action.id }) board.cancel(TransferKey(TransferDirection.RECEIVING, action.id))
            is ShelfAction.OnCancelTransfer -> board.cancel(action.key)
            ShelfAction.OnCancelAllTransfers -> board.cancelAll()
            ShelfAction.OnClearFinishedTransfers -> board.clearFinished()
            ShelfAction.OnTransfersClick -> _state.update { it.copy(isTransfersVisible = true) }
            ShelfAction.OnTransfersDismiss -> _state.update { it.copy(isTransfersVisible = false) }
            is ShelfAction.OnRetrySend -> resend(action.id)
            ShelfAction.OnAirGestureToggle -> toggleAirGestures()
            is ShelfAction.OnCameraPermission -> onCameraPermission(action.granted, action.justAsked)
            is ShelfAction.OnScreenVisibilityChanged -> {
                isScreenVisible = action.isVisible
                updateWatching()
            }
            is ShelfAction.OnCardGrabbed -> holdCard(action)
            ShelfAction.OnCatchTap -> catchCard(x = 0.5f, y = 0.35f)
            ShelfAction.OnPutBackTap -> held?.let { putBack(it, tellPeer = true) }
        }
    }

    private fun observeItems() {
        repository.observeItems()
            .onEach { items ->
                val now = clock.now()
                shelfItems = items.map { it.toUi(now, timeZone) }
                publishItems()
                playLocalCopy()
            }
            .launchIn(viewModelScope)
        // A send begun by an earlier screen ends while this one is open: its item goes, or comes back.
        sentIds.ids
            .onEach { if (!_state.value.isLoading) publishItems() }
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
     * A card with [id] is on this screen: on the shelf, waiting for its file, coming in from the peer, or held over
     * from the peer (or just caught from it). A received item counts from the moment it is known, before the shelf's
     * own list catches up with it.
     */
    private fun isOnScreen(id: String): Boolean =
        _state.value.items.any { it.id == id } ||
            arriving.any { it.id == id } ||
            incoming?.start?.itemId == id ||
            catchable?.start?.itemId == id ||
            id in caughtIds ||
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
                hintPlaying()
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

    /**
     * Tells the peer which of this shelf's videos plays here, and lets it read that file's header and index: it reads them
     * ahead, so its half of the card shows a frame sooner when the card is lifted (they cost 1.2 to 3.2 s of round
     * trips at the lift otherwise). Only once the link is on Wi-Fi.
     */
    private fun hintPlaying() {
        val media = peer?.media ?: return
        // A video followed from the peer is the peer's to announce.
        if (!media.isFast || following != null) return
        val id = _state.value.playing?.itemId ?: return
        val item = shelfItems.firstOrNull { it.id == id } ?: return
        // Lifted or held: all of it is readable already, and offered.
        if (outgoing?.id == id || held?.id == id) return
        // Only its header and index: its pictures and sound stay here until its card is lifted.
        media.shareIndex(item.id, item.uri, item.sizeBytes)
        post(SlideMessage.PlaybackHint(item.id, item.sizeBytes))
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
        syncCardInAir()
        transferScope.launch {
            if (!handOver(slide, edge)) {
                eventChannel.send(ShelfEvent.ShowSent(id = id, name = item.name))
                return@launch
            }
            keepAlive.keeping("Sending “${item.name}”") { deliverFile(item, peerMayFetch = slide?.offeredVideo == true) }
        }
    }

    /**
     * The file follows the card; the item leaves this shelf for good only once the peer has all of it. When the card
     * carried its video, the peer may take the file from what it already read ([peerMayFetch]).
     */
    private suspend fun deliverFile(item: ShelfItemUi, peerMayFetch: Boolean = false) {
        val peer = peer ?: return finishSend(item.id)
        val key = TransferKey(TransferDirection.SENDING, item.id)
        val progress = board.add(key, item.name, item.mimeType, item.sizeBytes) { peer.cancelSend(item.id) }
        var delivered: EmptyResult<DataError>? = null
        try {
            delivered = peer.send(OutgoingFile(itemId = item.id, uri = item.uri, peerMayFetch = peerMayFetch), progress)
        } finally {
            // Cut short (the app-wide scope ended) counts as stopped.
            board.finish(key, delivered.errorOr(DataError.Network.CANCELLED))
        }
        when (val outcome = delivered) {
            is Result.Success -> finishSend(item.id)
            // Stopped by either user: the item is simply back on this shelf.
            is Result.Failure -> {
                undoSend(item.id)
                if (outcome.error != DataError.Network.CANCELLED) {
                    eventChannel.send(ShelfEvent.ShowSendFailed(id = item.id, name = item.name, error = outcome.error))
                }
            }
            null -> Unit
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
        syncCardInAir()
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
                // Without a start the peer ignores the rest of the slide, so the card can't be handed over.
                if (announce(peer, start, slide, sharesVideo) is Result.Failure) slide.startFailed = true
            }
            if (sharesVideo) reportPlayback(peer, slide.id) { outgoing === slide }
        }
    }

    /**
     * Sends [start] and, when [card] carries its playing video, the offer right behind it, with the position read as
     * it goes. Call it holding the send lock. Returns how the start went.
     */
    private suspend fun announce(
        peer: SlidePeer,
        start: SlideMessage.DragStart,
        card: CarriedCard,
        sharesVideo: Boolean,
    ): EmptyResult<DataError.Network> {
        val result = sendStart(peer, start)
        if (sharesVideo && result is Result.Success) {
            playbackReport(card.id, offer = true, httpUrl = card.videoUrl)?.let { offer ->
                // Sent or not, the peer may have it: it then answers how the file goes, and is waited for.
                card.offeredVideo = true
                peer.transport.send(offer)
            }
        }
        return result
    }

    /** Sends [start]; call it holding the send lock. */
    private suspend fun sendStart(peer: SlidePeer, start: SlideMessage.DragStart): EmptyResult<DataError.Network> {
        val result = peer.transport.send(start)
        if (result is Result.Failure && result.error == DataError.Network.PAYLOAD_TOO_LARGE && start.thumbnail != null) {
            // The picture made it too big for one message: the peer can draw the card without it.
            return peer.transport.send(start.copy(thumbnail = null))
        }
        return result
    }

    /**
     * When the lifted (or grabbed) card is the video playing here, lets the peer read its file, so the peer's half of
     * the card plays it too: over the Wi-Fi network both phones are on, or over the pairing link once it is fast enough.
     */
    private fun shareVideo(peer: SlidePeer, item: ShelfItemUi, card: CarriedCard): Boolean {
        if (videoEngine == null || _state.value.playing?.itemId != item.id) return false
        val media = peer.media ?: return false
        val url = media.share(item.id, item.uri)
        if (url == null && !media.isFast) {
            media.unshare(item.id)
            return false
        }
        card.videoUrl = url
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

    /** Reports where the video of [id] is a few times a second, for as long as its card [isInAir] (slid or held). */
    private suspend fun reportPlayback(peer: SlidePeer, id: String, isInAir: () -> Boolean) {
        while (true) {
            delay(PlaybackClockMillis)
            if (!isInAir()) return
            val sent = sendLock.withLock {
                val report = playbackReport(id, offer = false) ?: return@withLock false
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
        // On Bluetooth every message costs more: every other frame is enough for the peer to play the card back
        // evenly (it fills in between). The first move and the one after leaving the range always go.
        slide.frames++
        if (near && slide.isStreaming && peer.media?.isFast == false && slide.frames % 2 == 0) return
        slide.isStreaming = near
        slide.seq++
        slide.edge = edge
        slide.outwardMmPerS = density.xPxToMm(frame.outwardPxPerS(edge))
        slide.alongEdgeMmPerS = density.yPxToMm(frame.velocityYPxPerS)
        postMove(
            SlideMessage.DragMove(
                itemId = frame.id,
                seq = slide.seq,
                exitEdge = edge,
                alongEdgeMm = density.yPxToMm(frame.cardTopPx),
                penetrationMm = penetrationMm,
                outwardMmPerS = slide.outwardMmPerS,
                alongEdgeMmPerS = slide.alongEdgeMmPerS,
                tiltDegrees = frame.tiltDegrees,
                atMillis = slide.stamp(frame),
            ),
        )
    }

    private fun stopMirroring(id: String) {
        if (outgoing?.id != id) return
        outgoing = null
        syncCardInAir()
        // Back on this shelf: the peer stops reading its video. (A sent card's video stays readable: the peer plays on.)
        peer?.media?.unshare(id)
        post(SlideMessage.DragCancel(id))
        // Its video plays on here: the peer let go of what it read, so it reads the index ahead again.
        hintPlaying()
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

    /** Sends [move] as soon as the link is free, with any others made meanwhile (see [pendingMoves]). */
    private fun postMove(move: SlideMessage.DragMove) {
        val peer = peer ?: return
        pendingMoves.addLast(move)
        while (pendingMoves.size > MaxBatchedMoves) pendingMoves.removeFirst()
        if (moveSender?.isActive == true) return
        moveSender = transferScope.launch {
            while (pendingMoves.isNotEmpty()) {
                // Let go or put back meanwhile: its commit or cancel says where it went, and no move may follow it.
                val batch = pendingMoves.filter { it.itemId == outgoing?.id }
                pendingMoves.clear()
                when (batch.size) {
                    0 -> Unit
                    1 -> deliver(peer, batch.single())
                    else -> deliver(peer, SlideMessage.DragMoves(batch))
                }
            }
        }
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
        combine(peer.isLinked, peer.media?.isFastUpdates ?: flowOf(true)) { linked, fast -> linked to fast }
            .onEach { (linked, fast) ->
                _state.update { it.copy(isPaired = linked, isLinkSlow = linked && !fast) }
                if (!linked) dropAirCards()
                // The link just got onto Wi-Fi Direct: a video still read over the shared Wi-Fi moves onto it.
                if (linked && fast) following?.let { readOverLink(peer, it) }
                // And the peer reads ahead the index of a video playing here: its card may be lifted any moment.
                if (linked && fast) hintPlaying()
                updateWatching()
            }
            .launchIn(viewModelScope)
        peer.transport.incoming
            .onEach { onPeerMessage(peer, it) }
            .launchIn(viewModelScope)
    }

    /**
     * Whether a card is in the air between the phones, either way: slid, held in a fist, or coming from the peer. Files
     * pulled from the peer make way for it meanwhile (see [MediaChannel.holdFetches]).
     */
    private fun syncCardInAir() {
        val inAir = outgoing != null || incoming != null || held != null || catchable != null
        peer?.media?.holdFetches(inAir)
    }

    private suspend fun onPeerMessage(peer: SlidePeer, message: SlideMessage) {
        try {
            handlePeerMessage(peer, message)
        } finally {
            syncCardInAir()
        }
    }

    private suspend fun handlePeerMessage(peer: SlidePeer, message: SlideMessage) {
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
                            widthPx = density.xMmToPx(peer.link.fromPeer(message.cardWidthMm)),
                            heightPx = density.yMmToPx(peer.link.fromPeer(message.cardHeightMm)),
                            thumbnail = message.thumbnail,
                        ),
                    ),
                )
            }
            is SlideMessage.DragMove -> onPeerMove(peer, message)
            // Sent together while the link was busy: played back exactly as if each had come alone.
            is SlideMessage.DragMoves -> message.moves.forEach { onPeerMove(peer, it) }
            is SlideMessage.DragCancel -> {
                val id = message.itemId
                when {
                    incoming?.start?.itemId == id -> incoming = null
                    catchable?.start?.itemId == id -> clearCatchable()
                    // Caught here just as the peer gave up holding it: its file won't come.
                    id in caughtIds -> {
                        peer.cancelReceive(id)
                        return
                    }
                    else -> return
                }
                if (following?.itemId == id || _state.value.playing?.itemId == id) stopVideo()
                // What was read of its video is no longer wanted.
                peer.media?.forget(id)
                eventChannel.send(ShelfEvent.PeerCardWithdrawn(id))
            }
            is SlideMessage.PlaybackOffer -> {
                peerCard(message.itemId)?.videoOffered = true
                followPeerVideo(peer, message)
            }
            is SlideMessage.Grabbed -> {
                val slide = incoming?.takeIf { it.start.itemId == message.itemId } ?: return
                val mine = held
                if (mine != null) {
                    // Both phones saw the same fist close: the one it was closer to (bigger in its camera) keeps
                    // its grab, the other puts its card back. Both sides decide alike, so exactly one grab stays.
                    if (!message.winsOver(mine)) return
                    putBack(mine, tellPeer = true)
                }
                // Held, not slid: it waits for a hand to open here instead of following moves in from the edge. Its
                // video (if it carries one) keeps playing along here, muted, ready for the catch.
                incoming = null
                catchable = slide
                air = air.copy(catchable = CatchableCardUi(slide.start.itemId, slide.start.name))
                publishAir()
                updateWatching()
            }
            is SlideMessage.Released -> onPeerCaught(message.itemId)
            is SlideMessage.PlaybackHint -> {
                hinted = message
                // Only on Wi-Fi: Bluetooth is too slow to read ahead on, and carries the card messages too.
                peer.media?.takeIf { it.isFast }?.prefetch(message.itemId, message.sizeBytes)
            }
            is SlideMessage.PlaybackClock ->
                following?.takeIf { it.itemId == message.itemId }?.let { onPeerClock(it, message) }
            // Matched to their card by the peer link, which waits for them; line-up swipes are pairing's.
            is SlideMessage.FileOffer, is SlideMessage.FileRoute, is SlideMessage.FileQueued, is SlideMessage.FileCancelled,
            is SlideMessage.FileKept, is SlideMessage.SwipeCrossed,
            -> Unit
            is SlideMessage.DragCommit -> {
                val slide = incoming?.takeIf { it.start.itemId == message.itemId } ?: return
                incoming = null
                val start = slide.start
                val letGo = slide.exitEdge?.let { edge ->
                    incomingCardVelocity(
                        SlideMessage.DragMove(
                            itemId = start.itemId,
                            seq = 0,
                            exitEdge = edge,
                            alongEdgeMm = 0f,
                            penetrationMm = 0f,
                            outwardMmPerS = message.outwardMmPerS,
                            alongEdgeMmPerS = message.alongEdgeMmPerS,
                            tiltDegrees = 0f,
                        ),
                        peer.link,
                    )
                }
                land(
                    peer,
                    slide,
                    ShelfEvent.PeerCardArrived(
                        id = start.itemId,
                        velocityXPxPerS = letGo?.let { density.xMmToPx(it.leftMm) } ?: 0f,
                        velocityYPxPerS = letGo?.let { density.yMmToPx(it.topMm) } ?: 0f,
                    ),
                )
            }
        }
    }

    /** One move of the peer's card in the air. */
    private suspend fun onPeerMove(peer: SlidePeer, message: SlideMessage.DragMove) {
        val slide = incoming?.takeIf { it.start.itemId == message.itemId && message.seq > it.lastSeq } ?: return
        slide.lastSeq = message.seq
        slide.exitEdge = message.exitEdge
        // Where the card was when the move left the peer; the screen plays the moves back evenly and draws
        // the card where it is by now.
        val link = peer.link
        val position = incomingCardPosition(
            move = message,
            cardWidthMm = slide.start.cardWidthMm,
            receiverWidthMm = density.xPxToMm(viewportWidthPx.toFloat()),
            link = link,
        )
        val velocity = incomingCardVelocity(message, link)
        eventChannel.send(
            ShelfEvent.PeerCardMoved(
                id = message.itemId,
                leftPx = density.xMmToPx(position.leftMm),
                topPx = density.yMmToPx(position.topMm),
                velocityXPxPerS = density.xMmToPx(velocity.leftMm),
                velocityYPxPerS = density.yMmToPx(velocity.topMm),
                tiltDegrees = message.tiltDegrees,
                arrival = message.arrival(link),
                entryEdge = message.exitEdge.opposite,
                sentAtMillis = message.atMillis,
                leadMillis = roundTripMillis / 2f,
            ),
        )
    }

    /** The peer's card with [id], slid in or held over, while it is still coming. */
    private fun peerCard(id: String): IncomingSlide? =
        incoming?.takeIf { it.start.itemId == id } ?: catchable?.takeIf { it.start.itemId == id }

    /**
     * The peer's card is ours, let go past the edge or caught by a hand: [arrived] plays its arrival, and it waits for
     * its file in its slot. A video it carried plays on here, with sound. Returns the wait for the file.
     */
    private suspend fun land(peer: SlidePeer, slide: IncomingSlide, arrived: ShelfEvent): Job {
        val start = slide.start
        // Its slot opens at the top of the shelf at once; the card flies into it and waits there for its file.
        arriving.removeAll { it.id == start.itemId }
        arriving.add(0, ArrivingItemUi(start.itemId, start.name, aspectRatio = start.aspectRatio()))
        publishItems()
        val video = following?.takeIf { it.itemId == start.itemId && it.isLoaded }
        // Lifted while the link was on Bluetooth, let go once it is on Wi-Fi Direct: read over it from here on.
        if (video != null) readOverLink(peer, video)
        val delivery = when {
            video?.viaLink == true -> Delivery.FETCH
            // On Wi-Fi, pulled in pieces that make way for the cards' moves.
            peer.media?.isFast == true -> Delivery.PULL
            slide.videoOffered -> Delivery.ASK_TO_SEND
            else -> Delivery.SENT
        }
        takeOverPeerVideo(start.itemId)
        eventChannel.send(arrived)
        // In its own coroutine: messages keep flowing while the file comes in, and leaving the screen doesn't stop it.
        return transferScope.launch {
            keepAlive.keeping("Receiving “${start.name}”") {
                // Read over the shared Wi-Fi, the video must move to its own file once that is here.
                receive(peer, start, delivery, movesToOwnFile = video != null && !video.viaLink)
            }
        }
    }

    private suspend fun receive(
        peer: SlidePeer,
        start: SlideMessage.DragStart,
        delivery: Delivery = Delivery.SENT,
        movesToOwnFile: Boolean = false,
    ) {
        receivingNames += start.name
        val file = IncomingFile(start.itemId, start.name, start.mimeType, start.sizeBytes, delivery)
        val key = TransferKey(TransferDirection.RECEIVING, start.itemId)
        // Its progress goes to the board, not the shelf's state: the grid doesn't redraw on every percent.
        val progress = board.add(key, start.name, start.mimeType, start.sizeBytes) { peer.cancelReceive(start.itemId) }
        var outcome: Result<String, DataError>? = null
        val result = try {
            peer.receive(file, progress).also { outcome = it }
        } finally {
            // Also when the wait is called off (a catch the peer never heard about): that counts as stopped.
            board.finish(key, outcome.errorOr(DataError.Network.CANCELLED))
            receivingNames.remove(start.name)
            caughtIds.remove(start.itemId)
        }
        arriving.removeAll { it.id == start.itemId }
        when (result) {
            is Result.Success -> {
                // The item takes the arriving card's place in the same update, and plays on if its card did.
                arrivedFrom[result.data] = start.itemId
                val playing = _state.value.playing
                if (playing?.itemId == start.itemId) {
                    _state.update { it.copy(playing = playing.copy(itemId = result.data)) }
                    // Read over the pairing link, it goes on from the kept file by itself (see MediaChannel.settle).
                    if (movesToOwnFile) streamedFromPeer = result.data
                }
                publishItems()
                playLocalCopy()
            }
            is Result.Failure -> {
                publishItems()
                eventChannel.send(ShelfEvent.PeerCardWithdrawn(start.itemId))
                // A transfer this user stopped needs no message.
                if (result.error != DataError.Network.CANCELLED) eventChannel.send(ShelfEvent.ShowError(result.error))
            }
        }
    }

    /**
     * The peer's lifted card is its playing video: play it here too, read from the peer as it plays, from the frame
     * the peer is at, muted until the card is let go here.
     */
    private fun followPeerVideo(peer: SlidePeer, offer: SlideMessage.PlaybackOffer) {
        val engine = videoEngine ?: return
        val media = peer.media ?: return
        if (peerCard(offer.itemId) == null) return
        // Anything kept from an earlier file with this id (ids come back once an item is gone) is not this one.
        // Unless it is the video the peer said was playing: its index, read ahead since, is what makes it start sooner.
        if (hinted?.let { it.itemId == offer.itemId && it.sizeBytes == offer.sizeBytes } != true) media.forget(offer.itemId)
        val follower = PlaybackFollower()
        follower.report(offer.positionMs, offer.isPlaying, receivedAtMs = nowMillis(), oneWayMs = oneWayMillis())
        val video = FollowedVideo(offer.itemId, offer.sizeBytes, follower)
        following = video
        viewModelScope.launch {
            // Over the pairing link once it is on Wi-Fi Direct: tried on phones, the shared Wi-Fi was far slower then
            // (the one radio hops between the two networks, and the router carries every byte twice). The shared
            // Wi-Fi is for while the pairing link is still on Bluetooth.
            val url = if (media.isFast) null else offer.httpUrl?.takeIf { media.canReach(it) }
            if (following !== video) return@launch
            val source = when {
                media.isFast -> {
                    video.viaLink = true
                    VideoSource.Remote(offer.itemId, offer.sizeBytes) { media.open(offer.itemId, it) }
                }
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

    /**
     * Moves a followed video read over the shared Wi-Fi onto the pairing link, once that is on Wi-Fi Direct, on the
     * frame the peer is at. Left on the shared Wi-Fi it starves: the one radio hops between the two networks, and the
     * file's own transfer takes the link (measured: 1.5 s of play for every 10 to 30 s of buffering).
     */
    private fun readOverLink(peer: SlidePeer, video: FollowedVideo) {
        val engine = videoEngine ?: return
        val media = peer.media ?: return
        if (!video.isLoaded || video.viaLink || !media.isFast) return
        video.viaLink = true
        val source = VideoSource.Remote(video.itemId, video.sizeBytes) { media.open(video.itemId, it) }
        val startAtMs = video.follower.startAt(nowMillis()) ?: engine.positionMs
        engine.load(source, startAtMs = startAtMs, playWhenReady = engine.playWhenReady.value)
        // A load starts at full volume: the peer's speaker still plays it while the card is over there.
        engine.setVolume(0f)
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

    /**
     * Moves a received video still read from the peer over the shared Wi-Fi onto its own file, on the same frame. The
     * peer stops serving it once it has sent the file: its item goes, and its access to the file with it.
     */
    private fun playLocalCopy() {
        val id = streamedFromPeer ?: return
        val engine = videoEngine ?: return
        if (_state.value.playing?.itemId != id) {
            // Stopped or replaced meanwhile.
            streamedFromPeer = null
            return
        }
        val item = shelfItems.firstOrNull { it.id == id } ?: return
        streamedFromPeer = null
        engine.load(VideoSource.Local(item.uri), startAtMs = engine.positionMs, playWhenReady = engine.playWhenReady.value)
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    private fun oneWayMillis(): Long = (roundTripMillis / 2f).toLong()

    private fun SlideMessage.DragStart.aspectRatio(): Float =
        if (cardHeightMm > 0f) (cardWidthMm / cardHeightMm).coerceIn(0.5f, 2f) else 1f

    // endregion

    // region Air gestures: a fist grabs a card here, a hand opening in front of the peer drops it there.

    private fun publishAir() {
        _state.update { it.copy(air = air) }
    }

    private fun toggleAirGestures() {
        if (hands == null) return
        when {
            air.isOn -> setAirGestures(on = false)
            isCameraPermitted -> setAirGestures(on = true)
            else -> {
                turnOnWhenPermitted = true
                viewModelScope.launch { eventChannel.send(ShelfEvent.RequestCameraPermission) }
            }
        }
    }

    private fun onCameraPermission(granted: Boolean, justAsked: Boolean) {
        if (granted && !isCameraPermitted) cameraFailed = false
        isCameraPermitted = granted
        if (justAsked && turnOnWhenPermitted) {
            if (granted) {
                setAirGestures(on = true)
            } else {
                viewModelScope.launch { eventChannel.send(ShelfEvent.ShowError(DataError.Local.CAMERA_UNAVAILABLE)) }
            }
        }
        if (justAsked) turnOnWhenPermitted = false
        updateWatching()
    }

    private fun setAirGestures(on: Boolean) {
        turnOnWhenPermitted = false
        if (on) cameraFailed = false
        if (!on) held?.let { putBack(it, tellPeer = true) }
        air = air.copy(isOn = on)
        publishAir()
        updateWatching()
    }

    /**
     * The camera watches only while it can be useful: the shelf is on screen, a phone is paired, and either this user
     * turned air gestures on or the peer's user is holding a card over to this phone.
     */
    private fun updateWatching() {
        val hands = hands ?: return
        val wanted = isScreenVisible && isCameraPermitted && !cameraFailed && _state.value.isPaired &&
            (air.isOn || catchable != null)
        if (wanted == (watchJob != null)) return
        if (!wanted) {
            watchJob?.cancel()
            watchJob = null
            _hand.value = null
        } else {
            detector.reset()
            smoother.reset()
            val job = hands.frames()
                .onEach(::onHandFrame)
                .catch {
                    watchJob = null
                    cameraFailed = true
                    _hand.value = null
                    air = air.copy(isOn = false, isWatching = false)
                    publishAir()
                    eventChannel.send(ShelfEvent.ShowError(DataError.Local.CAMERA_UNAVAILABLE))
                }
                .launchIn(viewModelScope)
            // A camera that failed straight away has been given up on already.
            watchJob = job.takeIf { it.isActive }
        }
        air = air.copy(isWatching = watchJob != null)
        publishAir()
    }

    private suspend fun onHandFrame(frame: HandFrame) {
        // Drawn smoothed and slightly ahead; gestures use the raw positions.
        _hand.value = if (frame.pose == HandPose.NONE) {
            null
        } else {
            val (x, y) = smoother.smooth(frame.x, frame.y, frame.atMillis)
            HandPointUi(x, y, frame.pose == HandPose.FIST)
        }
        when (val gesture = detector.onFrame(frame)) {
            is AirGesture.Grab -> if (canGrab()) {
                pendingGrab = gesture
                eventChannel.send(ShelfEvent.GrabCardAt(gesture.x, gesture.y))
            }
            // A hand opening catches the peer's card here; with a card in this hand, it puts it back instead (unless
            // the peer catches it meanwhile). A hand that comes in already open counts too: the fist it carried may
            // never have been in this camera's view.
            is AirGesture.Release, is AirGesture.Open -> onHandOpened(gesture)
            null -> Unit
        }
    }

    private fun onHandOpened(gesture: AirGesture) {
        when {
            catchable != null -> {
                detector.requireHandAway()
                catchCard(gesture.x, gesture.y)
            }
            else -> held?.let { card ->
                detector.requireHandAway()
                if (card.putBackJob?.isActive != true) {
                    card.putBackJob = viewModelScope.launch {
                        delay(PutBackGrace)
                        putBack(card, tellPeer = true)
                    }
                }
            }
        }
    }

    private fun canGrab(): Boolean =
        air.isOn && held == null && catchable == null && outgoing == null && _state.value.isPaired &&
            _state.value.items.isNotEmpty()

    /**
     * The card picked for a grab is in the hand: the peer learns about it, and waits for a hand to open there. When it
     * is the video playing here, it carries the video like a slid card: the peer plays along, muted, until the catch.
     */
    private fun holdCard(action: ShelfAction.OnCardGrabbed) {
        val peer = peer
        val item = shelfItems.firstOrNull { it.id == action.id && it.id !in sentIds }
        if (peer == null || item == null || held != null || !canGrabNow()) {
            if (held?.id != action.id) viewModelScope.launch { eventChannel.send(ShelfEvent.HeldCardPutBack(action.id)) }
            return
        }
        val card = HeldCard(item.id, handSize = pendingGrab?.size ?: 0f)
        pendingGrab = null
        held = card
        syncCardInAir()
        air = air.copy(heldId = item.id, heldName = item.name)
        publishAir()
        val start = SlideMessage.DragStart(
            itemId = item.id,
            name = item.name,
            mimeType = item.mimeType,
            sizeBytes = item.sizeBytes,
            cardWidthMm = density.xPxToMm(action.cardWidthPx),
            cardHeightMm = density.yPxToMm(action.cardHeightPx),
            thumbnail = action.thumbnail,
        )
        val sharesVideo = shareVideo(peer, item, card)
        transferScope.launch {
            val announced = sendLock.withLock { announce(peer, start, card, sharesVideo) }
            val grabbed = if (announced is Result.Failure) {
                announced
            } else {
                deliver(peer, SlideMessage.Grabbed(item.id, card.handSize))
            }
            if (grabbed is Result.Failure) {
                if (held === card) {
                    putBack(card, tellPeer = true)
                    eventChannel.send(ShelfEvent.ShowError(grabbed.error))
                }
            } else if (sharesVideo) {
                reportPlayback(peer, card.id) { held === card }
            }
        }
        card.expiry = viewModelScope.launch {
            delay(HoldLimit)
            if (held === card) putBack(card, tellPeer = true)
        }
    }

    /** Like [canGrab], minus the card that may already be on its way into the hand. */
    private fun canGrabNow(): Boolean = air.isOn && catchable == null && outgoing == null && _state.value.isPaired

    private fun putBack(card: HeldCard, tellPeer: Boolean) {
        if (held !== card) return
        held = null
        syncCardInAir()
        card.expiry?.cancel()
        card.putBackJob?.cancel()
        air = air.copy(heldId = null, heldName = null)
        publishAir()
        // Back on this shelf: the peer stops reading its video.
        peer?.media?.unshare(card.id)
        if (tellPeer) post(SlideMessage.DragCancel(card.id))
        if (tellPeer) hintPlaying()
        viewModelScope.launch { eventChannel.send(ShelfEvent.HeldCardPutBack(card.id)) }
    }

    /**
     * A hand opened in front of the peer: the held card leaves this shelf, and its file follows (a video it carried
     * stays readable, so the peer plays on and may take the file from what it read).
     */
    private suspend fun onPeerCaught(id: String) {
        val card = held?.takeIf { it.id == id }
        val item = shelfItems.firstOrNull { it.id == id }
        if (card == null || item == null) {
            // Not held any more (put back or timed out just before): the peer must not wait for a file.
            post(SlideMessage.DragCancel(id))
            card?.let { putBack(it, tellPeer = false) }
            return
        }
        held = null
        syncCardInAir()
        card.expiry?.cancel()
        card.putBackJob?.cancel()
        air = air.copy(heldId = null, heldName = null)
        publishAir()
        sentIds.add(id)
        publishItems()
        eventChannel.send(ShelfEvent.HeldCardCaught(id))
        transferScope.launch {
            keepAlive.keeping("Sending “${item.name}”") { deliverFile(item, peerMayFetch = card.offeredVideo) }
        }
    }

    /**
     * Catches the peer's held card at ([x], [y]) on this screen. The card lands at once, while the peer is told: waiting
     * for the message to go out first made the catch feel late. If it can't go out, the landing is undone.
     */
    private fun catchCard(x: Float, y: Float) {
        val peer = peer ?: return
        val slide = catchable ?: return
        val id = slide.start.itemId
        clearCatchable()
        caughtIds += id
        transferScope.launch {
            val receiving = land(peer, slide, ShelfEvent.PeerCardCaught(id, x, y))
            val answered = deliver(peer, SlideMessage.Released(id))
            if (answered is Result.Failure) {
                // The peer never heard of the catch, so no file is coming (and its video stops here).
                receiving.cancel()
                caughtIds.remove(id)
                arriving.removeAll { it.id == id }
                publishItems()
                peer.media?.forget(id)
                eventChannel.send(ShelfEvent.PeerCardWithdrawn(id))
                eventChannel.send(ShelfEvent.ShowError(answered.error))
            }
        }
    }

    private fun clearCatchable() {
        catchable = null
        syncCardInAir()
        air = air.copy(catchable = null)
        publishAir()
        updateWatching()
    }

    /** The link dropped: whatever was held in the air, on either side, goes back. */
    private suspend fun dropAirCards() {
        held?.let { putBack(it, tellPeer = false) }
        val waiting = catchable ?: return
        clearCatchable()
        if (following?.itemId == waiting.start.itemId) stopVideo()
        eventChannel.send(ShelfEvent.PeerCardWithdrawn(waiting.start.itemId))
    }

    private fun SlideMessage.Grabbed.winsOver(mine: HeldCard): Boolean =
        if (handSize != mine.handSize) handSize > mine.handSize else itemId > mine.id

    // endregion

    /** A card carried off this shelf toward the peer, slid or held in a fist, and the video it may carry. */
    private open class CarriedCard(val id: String) {
        /** Where the peer reads this card's video over the shared Wi-Fi, when it is one. */
        var videoUrl: String? = null

        /** Its video was offered to the peer, which then says how the file goes once it has the card. */
        var offeredVideo = false
    }

    private class HeldCard(id: String, val handSize: Float) : CarriedCard(id) {
        var expiry: Job? = null
        var putBackJob: Job? = null
    }

    private inner class OutgoingSlide(id: String) : CarriedCard(id) {
        var seq = 0
        var frames = 0
        var isStreaming = false

        /** This phone's clock when the frame clock read 0, for stamping moves by when their frame was drawn. */
        private var frameClockOrigin: Long? = null

        /** When [frame] was drawn, by this phone's clock: frame times are evenly spaced, unlike when moves go out. */
        fun stamp(frame: SlideFrame): Long {
            val now = nowMillis()
            if (frame.frameTimeNanos == 0L) return now
            val frameMillis = frame.frameTimeNanos / 1_000_000
            val origin = frameClockOrigin ?: (now - frameMillis).also { frameClockOrigin = it }
            return origin + frameMillis
        }
        var startFailed = false
        var edge: ExitEdge? = null
        var outwardMmPerS = 0f
        var alongEdgeMmPerS = 0f
    }

    private class IncomingSlide(val start: SlideMessage.DragStart) {
        var lastSeq = -1

        /** The edge its moves left the peer through. */
        var exitEdge: ExitEdge? = null

        /** The peer offered its video with this card: it waits to hear how the file goes. */
        var videoOffered = false
    }

    private class FollowedVideo(val itemId: String, val sizeBytes: Long, val follower: PlaybackFollower) {
        /** Loaded into the engine: until then (finding out which way to read it) nothing is steered. */
        var isLoaded = false

        /**
         * Read in pieces over the pairing link (not over the shared Wi-Fi): those pieces are kept, so the file comes
         * from them, and the player goes on from the kept copy without reloading.
         */
        var viaLink = false

        /** Waiting on a frame for the peer to get there. */
        var hold: Job? = null
    }
}
