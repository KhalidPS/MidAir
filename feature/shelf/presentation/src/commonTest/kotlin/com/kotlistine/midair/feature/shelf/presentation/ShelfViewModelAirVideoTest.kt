package com.kotlistine.midair.feature.shelf.presentation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.kotlistine.midair.core.media.VideoSource
import com.kotlistine.midair.core.transfer.domain.ScreenDensity
import com.kotlistine.midair.core.transfer.domain.SlideMessage
import com.kotlistine.midair.feature.shelf.domain.Delivery
import com.kotlistine.midair.feature.shelf.domain.gesture.HandFrame
import com.kotlistine.midair.feature.shelf.domain.gesture.HandPose
import com.kotlistine.midair.feature.shelf.presentation.FakeShelfRepository.Companion.item
import com.kotlistine.midair.feature.shelf.presentation.model.PlayingVideoUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A playing video grabbed with a fist travels like a slid one: the peer plays along, muted, while it is held, and
 * plays on with sound from the same frame once a hand opens there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelAirVideoTest {

    private lateinit var repository: FakeShelfRepository
    private lateinit var engine: FakeVideoEngine
    private lateinit var media: FakeMediaChannel
    private lateinit var peer: FakePeer
    private lateinit var hands: FakeHandTracker
    private lateinit var viewModel: ShelfViewModel
    private var frameAt = 0L

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeShelfRepository()
        repository.items.value = listOf(
            item("1", uri = "content://videos/1", name = "Climb.mp4", mimeType = "video/mp4"),
            item("2", name = "Q4 Report.pdf"),
        )
        engine = FakeVideoEngine()
        media = FakeMediaChannel()
        hands = FakeHandTracker()
        // The file never finishes coming in here: what matters is how it was asked for.
        peer = FakePeer(onReceive = { CompletableDeferred<Nothing>().await() }, media = media)
        viewModel = ShelfViewModel(
            repository = repository,
            density = ScreenDensity(xdpi = 254f, ydpi = 254f),
            peer = peer,
            videoEngine = engine,
            hands = hands,
            clock = FixedClock,
            timeZone = TimeZone.UTC,
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Keeps the state flowing (it is shared only while observed) and puts the shelf on screen, camera allowed. */
    private fun TestScope.shelfOnScreen() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.onAction(ShelfAction.OnCameraPermission(granted = true))
        viewModel.onAction(ShelfAction.OnScreenVisibilityChanged(isVisible = true))
    }

    private suspend fun show(pose: HandPose, x: Float = 0.3f, y: Float = 0.6f) {
        repeat(4) {
            hands.frames.emit(HandFrame(pose, x, y, size = 0.4f, atMillis = frameAt))
            frameAt += 100
        }
    }

    /** Video 1 playing, air gestures on, and a fist that grabs it. */
    private suspend fun grabPlayingVideo(positionMs: Long = 12_000) {
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))
        engine.positionMs = positionMs
        viewModel.onAction(ShelfAction.OnAirGestureToggle)
        show(HandPose.OPEN)
        show(HandPose.FIST)
        viewModel.onAction(ShelfAction.OnCardGrabbed("1", cardWidthPx = 300f, cardHeightPx = 190f, thumbnail = null))
    }

    // region Grabbing a playing video here

    @Test
    fun `a grabbed playing video is offered to the peer between the card and the grab`() = runTest {
        shelfOnScreen()

        grabPlayingVideo(positionMs = 12_000)

        val sent = peer.transport.sentSlides
        assertThat(sent.map { it::class }).containsExactly(
            SlideMessage.DragStart::class,
            SlideMessage.PlaybackOffer::class,
            SlideMessage.Grabbed::class,
        )
        assertThat(sent[1]).isEqualTo(SlideMessage.PlaybackOffer("1", sizeBytes = 16_000, positionMs = 12_000, isPlaying = true))
        assertThat(media.shared).isEqualTo(mapOf("1" to "content://videos/1"))
        viewModel.onAction(ShelfAction.OnPutBackTap)
    }

    @Test
    fun `while the video is held in the fist the peer hears where it is`() = runTest {
        shelfOnScreen()
        grabPlayingVideo()
        engine.positionMs = 12_250

        advanceTimeBy(260)

        assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.PlaybackClock("1", 12_250, isPlaying = true))
        viewModel.onAction(ShelfAction.OnPutBackTap)
    }

    @Test
    fun `the video keeps playing here while it is held`() = runTest {
        shelfOnScreen()

        grabPlayingVideo()

        assertThat(viewModel.state.value.playing).isEqualTo(PlayingVideoUi("1", isPaused = false))
        assertThat(engine.releaseCount).isEqualTo(0)
        viewModel.onAction(ShelfAction.OnPutBackTap)
    }

    @Test
    fun `a held video put back stops being readable in full by the peer, and its reports stop`() = runTest {
        shelfOnScreen()
        grabPlayingVideo()

        viewModel.onAction(ShelfAction.OnPutBackTap)
        val sentBefore = peer.transport.sentSlides.size
        advanceTimeBy(1_000)

        // All of it stops being readable; its header and index stay, for the next time it is lifted.
        assertThat(media.shared).isEqualTo(emptyMap())
        assertThat(media.sharedIndex).isEqualTo(mapOf("1" to "content://videos/1"))
        assertThat(peer.transport.sentSlides.size).isEqualTo(sentBefore)
        assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.DragCancel("1"))
    }

    @Test
    fun `a caught video lets the peer say how its file goes, and stops playing here`() = runTest {
        shelfOnScreen()
        grabPlayingVideo()

        peer.transport.deliver(SlideMessage.Released("1"))

        assertThat(peer.sentFiles.single().peerMayFetch).isEqualTo(true)
        assertThat(viewModel.state.value.playing).isNull()
        // The peer plays on from this file until it has its own.
        assertThat(media.shared).isEqualTo(mapOf("1" to "content://videos/1"))
    }

    @Test
    fun `a grabbed card that isn't the playing video carries no video`() = runTest {
        shelfOnScreen()
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))
        viewModel.onAction(ShelfAction.OnAirGestureToggle)
        show(HandPose.OPEN)
        show(HandPose.FIST)

        viewModel.onAction(ShelfAction.OnCardGrabbed("2", cardWidthPx = 300f, cardHeightPx = 400f, thumbnail = null))

        assertThat(peer.transport.sentSlides.filterIsInstance<SlideMessage.PlaybackOffer>()).containsExactly()
        peer.transport.deliver(SlideMessage.Released("2"))
        assertThat(peer.sentFiles.single().peerMayFetch).isEqualTo(false)
    }

    // endregion

    // region Catching the peer's held video here

    private val peerStart = SlideMessage.DragStart(
        itemId = "9",
        name = "Climb.mp4",
        mimeType = "video/mp4",
        sizeBytes = 24_000_000,
        cardWidthMm = 40f,
        cardHeightMm = 25f,
        thumbnail = null,
    )

    /** The peer's user grabs their playing video: the card, its video, then the grab, as the peer sends them. */
    private suspend fun peerHoldsVideo() {
        peer.transport.deliver(peerStart)
        peer.transport.deliver(SlideMessage.PlaybackOffer("9", sizeBytes = 24_000_000, positionMs = 5_000, isPlaying = true))
        peer.transport.deliver(SlideMessage.Grabbed("9", handSize = 0.4f))
    }

    @Test
    fun `a video held over from the peer plays along here, muted, while it waits to be caught`() = runTest {
        shelfOnScreen()

        peerHoldsVideo()

        assertThat(engine.loaded).isNotNull().isInstanceOf(VideoSource.Remote::class)
        assertThat(engine.currentVolume).isEqualTo(0f)
        assertThat(viewModel.state.value.playing).isEqualTo(PlayingVideoUi("9", isPaused = false))
    }

    @Test
    fun `the peer's reports keep the held video on its frame`() = runTest {
        shelfOnScreen()
        peerHoldsVideo()

        peer.transport.deliver(SlideMessage.PlaybackClock("9", positionMs = 5_200, isPlaying = false))

        assertThat(viewModel.state.value.playing).isEqualTo(PlayingVideoUi("9", isPaused = true))
    }

    @Test
    fun `catching a held video plays it on with sound, its file taken from what was read`() = runTest {
        shelfOnScreen()
        peerHoldsVideo()
        engine.setSpeed(1.1f)

        show(HandPose.OPEN, x = 0.6f, y = 0.5f)

        assertThat(peer.transport.sentSlides).containsExactly(SlideMessage.Released("9"))
        assertThat(engine.currentVolume).isEqualTo(1f)
        assertThat(engine.currentSpeed).isEqualTo(1f)
        assertThat(engine.releaseCount).isEqualTo(0)
        assertThat(viewModel.state.value.playing).isEqualTo(PlayingVideoUi("9", isPaused = false))
        assertThat(peer.received.single().delivery).isEqualTo(Delivery.FETCH)
    }

    @Test
    fun `a held video that couldn't be played here asks for its file to be sent once caught`() = runTest {
        media.isFast = false
        media.reachable = false
        shelfOnScreen()
        peerHoldsVideo()

        viewModel.onAction(ShelfAction.OnCatchTap)

        assertThat(peer.received.single().delivery).isEqualTo(Delivery.ASK_TO_SEND)
    }

    @Test
    fun `the peer putting its held video back stops it here and lets go of what was read`() = runTest {
        shelfOnScreen()
        peerHoldsVideo()

        peer.transport.deliver(SlideMessage.DragCancel("9"))

        assertThat(viewModel.state.value.playing).isNull()
        assertThat(engine.releaseCount).isEqualTo(1)
        // Once as the offer came in (anything older with that id), once as it was withdrawn.
        assertThat(media.forgotten).containsExactly("9", "9")
    }

    // endregion

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }
}
