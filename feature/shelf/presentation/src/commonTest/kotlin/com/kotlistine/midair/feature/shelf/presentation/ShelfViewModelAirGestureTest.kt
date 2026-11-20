package com.kotlistine.midair.feature.shelf.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.feature.shelf.domain.gesture.HandFrame
import com.kotlistine.midair.feature.shelf.domain.gesture.HandPose
import com.kotlistine.midair.feature.shelf.domain.gesture.HandTrackingException
import com.kotlistine.midair.core.transfer.domain.ScreenDensity
import com.kotlistine.midair.core.transfer.domain.SlideMessage
import com.kotlistine.midair.feature.shelf.domain.IncomingFile
import com.kotlistine.midair.feature.shelf.domain.OutgoingFile
import com.kotlistine.midair.feature.shelf.presentation.FakeShelfRepository.Companion.item
import com.kotlistine.midair.feature.shelf.presentation.model.CatchableCardUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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

/** Air gestures with a linked device: grabbing a card with a fist here, catching the peer's with an open hand. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelAirGestureTest {

    private lateinit var repository: FakeShelfRepository
    private lateinit var peer: FakePeer
    private lateinit var hands: FakeHandTracker
    private lateinit var viewModel: ShelfViewModel

    /** 254 dpi: exactly 10 px per millimetre. */
    private val density = ScreenDensity(xdpi = 254f, ydpi = 254f)
    private var frameAt = 0L

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeShelfRepository()
        repository.items.value = listOf(item("1", name = "Q4 Report.pdf"))
        peer = FakePeer()
        hands = FakeHandTracker()
        viewModel = ShelfViewModel(
            repository = repository,
            density = density,
            peer = peer,
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
    private fun TestScope.shelfOnScreen(cameraAllowed: Boolean = true) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        viewModel.onAction(ShelfAction.OnCameraPermission(cameraAllowed))
        viewModel.onAction(ShelfAction.OnScreenVisibilityChanged(isVisible = true))
    }

    private val air get() = viewModel.state.value.air

    /** [pose] in front of the camera for 400 ms, a picture every 100 ms. */
    private suspend fun show(pose: HandPose, x: Float = 0.3f, y: Float = 0.6f, size: Float = 0.4f) {
        repeat(4) {
            hands.frames.emit(HandFrame(pose, x, y, size, atMillis = frameAt))
            frameAt += 100
        }
    }

    private suspend fun grabGesture(x: Float = 0.3f, y: Float = 0.6f) {
        show(HandPose.OPEN, x, y)
        show(HandPose.FIST, x, y)
    }

    private suspend fun releaseGesture(x: Float = 0.5f, y: Float = 0.4f) {
        show(HandPose.FIST, x, y)
        show(HandPose.OPEN, x, y)
    }

    private val peerStart = SlideMessage.DragStart(
        itemId = "9",
        name = "River Valley.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 395_000,
        cardWidthMm = 30f,
        cardHeightMm = 40f,
        thumbnail = null,
    )

    /** Air gestures on, a fist made over the shelf, and card 1 grabbed in it. */
    private suspend fun holdCardOne(thumbnail: ByteArray? = null) {
        viewModel.onAction(ShelfAction.OnAirGestureToggle)
        grabGesture()
        viewModel.onAction(ShelfAction.OnCardGrabbed("1", cardWidthPx = 300f, cardHeightPx = 400f, thumbnail))
    }

    // region Turning it on

    @Test
    fun `air gestures ask for the camera first, and turn on once it is allowed`() = runTest {
        shelfOnScreen(cameraAllowed = false)
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnAirGestureToggle)
            assertThat(awaitItem()).isEqualTo(ShelfEvent.RequestCameraPermission)
            assertThat(air.isOn).isFalse()

            viewModel.onAction(ShelfAction.OnCameraPermission(granted = true, justAsked = true))

            assertThat(air.isOn).isTrue()
            assertThat(air.isWatching).isTrue()
            assertThat(hands.watchers).isEqualTo(1)
        }
    }

    @Test
    fun `a refused camera leaves air gestures off and says why`() = runTest {
        shelfOnScreen(cameraAllowed = false)
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnAirGestureToggle)
            awaitItem()

            viewModel.onAction(ShelfAction.OnCameraPermission(granted = false, justAsked = true))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Local.CAMERA_UNAVAILABLE))
            assertThat(air.isOn).isFalse()
        }
    }

    @Test
    fun `the camera watches only while the shelf is on screen`() = runTest {
        shelfOnScreen()
        viewModel.onAction(ShelfAction.OnAirGestureToggle)
        assertThat(hands.watchers).isEqualTo(1)

        viewModel.onAction(ShelfAction.OnScreenVisibilityChanged(isVisible = false))
        assertThat(hands.watchers).isEqualTo(0)
        assertThat(air.isWatching).isFalse()

        viewModel.onAction(ShelfAction.OnScreenVisibilityChanged(isVisible = true))
        assertThat(hands.watchers).isEqualTo(1)
    }

    @Test
    fun `a camera that fails turns air gestures off`() = runTest {
        hands.failure = HandTrackingException("busy")
        shelfOnScreen()
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnAirGestureToggle)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Local.CAMERA_UNAVAILABLE))
            assertThat(air.isOn).isFalse()
            assertThat(air.isWatching).isFalse()
        }
    }

    @Test
    fun `a camera blocked in the privacy settings says so, so the user can be sent there`() = runTest {
        hands.failure = HandTrackingException("blocked", error = DataError.Local.CAMERA_BLOCKED)
        shelfOnScreen()
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnAirGestureToggle)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Local.CAMERA_BLOCKED))
            assertThat(air.isOn).isFalse()
        }
    }

    // endregion

    // region Grabbing a card here

    @Test
    fun `a fist made over the shelf grabs the card nearest to it`() = runTest {
        shelfOnScreen()
        viewModel.onAction(ShelfAction.OnAirGestureToggle)
        viewModel.events.test {
            grabGesture(x = 0.7f, y = 0.2f)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.GrabCardAt(0.7f, 0.2f))
        }
    }

    @Test
    fun `a fist means nothing while air gestures are off`() = runTest {
        shelfOnScreen()
        viewModel.events.test {
            grabGesture()

            expectNoEvents()
            assertThat(hands.watchers).isEqualTo(0)
        }
    }

    @Test
    fun `a grabbed card is announced to the peer as held, with the size of the fist`() = runTest {
        shelfOnScreen()
        val thumbnail = byteArrayOf(1, 2, 3)

        holdCardOne(thumbnail)

        val (start, grabbed) = peer.transport.sentSlides
        start as SlideMessage.DragStart
        assertThat(start.itemId).isEqualTo("1")
        assertThat(start.cardWidthMm).isEqualTo(30f)
        assertThat(start.thumbnail).isEqualTo(thumbnail)
        assertThat(grabbed).isEqualTo(SlideMessage.Grabbed("1", handSize = 0.4f))
        assertThat(air.heldId).isEqualTo("1")
        assertThat(air.heldName).isEqualTo("Q4 Report.pdf")
    }

    @Test
    fun `when a hand opens in front of the peer, the card leaves this shelf and its file follows`() = runTest {
        shelfOnScreen()
        viewModel.events.test {
            holdCardOne()
            assertThat(awaitItem()).isEqualTo(ShelfEvent.GrabCardAt(0.3f, 0.6f))
            peer.transport.deliver(SlideMessage.Released("1"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.HeldCardCaught("1"))
            assertThat(viewModel.state.value.items.map { it.id }).containsExactly()
            assertThat(peer.sentFiles).containsExactly(OutgoingFile("1", "content://file/1"))
            assertThat(air.heldId).isNull()
        }
    }

    @Test
    fun `opening the hand back here puts the card back, after a moment for the peer to catch it`() = runTest {
        shelfOnScreen()
        viewModel.events.test {
            holdCardOne()
            assertThat(awaitItem()).isEqualTo(ShelfEvent.GrabCardAt(0.3f, 0.6f))
            releaseGesture()
            expectNoEvents()

            advanceTimeBy(900)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.HeldCardPutBack("1"))
            assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.DragCancel("1"))
            assertThat(air.heldId).isNull()
        }
    }

    @Test
    fun `the peer catching the card during that moment wins`() = runTest {
        shelfOnScreen()
        viewModel.events.test {
            holdCardOne()
            assertThat(awaitItem()).isEqualTo(ShelfEvent.GrabCardAt(0.3f, 0.6f))
            releaseGesture()
            peer.transport.deliver(SlideMessage.Released("1"))
            assertThat(awaitItem()).isEqualTo(ShelfEvent.HeldCardCaught("1"))

            advanceTimeBy(2_000)

            expectNoEvents()
            assertThat(peer.transport.sentSlides.none { it is SlideMessage.DragCancel }).isTrue()
        }
    }

    @Test
    fun `a card nobody takes goes back on its own`() = runTest {
        shelfOnScreen()
        viewModel.events.test {
            holdCardOne()
            assertThat(awaitItem()).isEqualTo(ShelfEvent.GrabCardAt(0.3f, 0.6f))
            advanceTimeBy(11_000)
            expectNoEvents()

            advanceTimeBy(2_000)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.HeldCardPutBack("1"))
            assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.DragCancel("1"))
        }
    }

    @Test
    fun `tapping the hint puts the held card back at once`() = runTest {
        shelfOnScreen()
        viewModel.events.test {
            holdCardOne()
            assertThat(awaitItem()).isEqualTo(ShelfEvent.GrabCardAt(0.3f, 0.6f))

            viewModel.onAction(ShelfAction.OnPutBackTap)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.HeldCardPutBack("1"))
            assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.DragCancel("1"))
            assertThat(air.heldName).isNull()
        }
    }

    @Test
    fun `a hand coming back open over this phone puts the card back, even without a fist seen first`() = runTest {
        shelfOnScreen()
        viewModel.events.test {
            holdCardOne()
            assertThat(awaitItem()).isEqualTo(ShelfEvent.GrabCardAt(0.3f, 0.6f))
            // The fist left the view, and the hand came back already open.
            show(HandPose.NONE)
            show(HandPose.OPEN)

            advanceTimeBy(900)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.HeldCardPutBack("1"))
        }
    }

    @Test
    fun `a catch for a card no longer held tells the peer not to wait for it`() = runTest {
        shelfOnScreen()

        peer.transport.deliver(SlideMessage.Released("1"))

        assertThat(peer.transport.sentSlides).containsExactly(SlideMessage.DragCancel("1"))
        assertThat(peer.sentFiles).containsExactly()
    }

    // endregion

    // region Catching the peer's card here

    private suspend fun peerHolds(handSize: Float = 0.4f) {
        peer.transport.deliver(peerStart)
        peer.transport.deliver(SlideMessage.Grabbed("9", handSize))
    }

    @Test
    fun `a card held over from the peer is offered, and the camera watches for the hand, even with air gestures off`() =
        runTest {
            shelfOnScreen()

            peerHolds()

            assertThat(air.catchable).isEqualTo(CatchableCardUi("9", "River Valley.jpg"))
            assertThat(air.isWatching).isTrue()
        }

    @Test
    fun `opening a hand here catches the peer's card where the hand is`() = runTest {
        shelfOnScreen()
        peerHolds()
        viewModel.events.test {
            awaitItem() // the peer's card was lifted

            releaseGesture(x = 0.5f, y = 0.4f)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardCaught("9", 0.5f, 0.4f))
            assertThat(peer.transport.sentSlides).containsExactly(SlideMessage.Released("9"))
            assertThat(peer.received).containsExactly(IncomingFile("9", "River Valley.jpg", "image/jpeg", 395_000))
            assertThat(air.catchable).isNull()
            assertThat(hands.watchers).isEqualTo(0)
        }
    }

    @Test
    fun `a hand coming in already open catches the peer's card`() = runTest {
        shelfOnScreen()
        peerHolds()
        viewModel.events.test {
            awaitItem() // the peer's card was lifted

            // This camera never saw the fist: it closed over the other phone.
            show(HandPose.OPEN, x = 0.6f, y = 0.5f)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardCaught("9", 0.6f, 0.5f))
            assertThat(peer.transport.sentSlides).containsExactly(SlideMessage.Released("9"))
        }
    }

    @Test
    fun `an open hand already in view before the card came over doesn't catch it`() = runTest {
        shelfOnScreen()
        viewModel.onAction(ShelfAction.OnAirGestureToggle)
        show(HandPose.OPEN)
        peerHolds()
        viewModel.events.test {
            awaitItem() // the peer's card was lifted

            show(HandPose.OPEN)

            expectNoEvents()
            assertThat(air.catchable).isEqualTo(CatchableCardUi("9", "River Valley.jpg"))
        }
    }

    @Test
    fun `a catch the peer never hears about is undone`() = runTest {
        peer = FakePeer(onReceive = { awaitCancellation() })
        peer.transport.failWith = { if (it is SlideMessage.Released) DataError.Network.DISCONNECTED else null }
        viewModel = ShelfViewModel(repository, density, peer = peer, hands = hands, clock = FixedClock, timeZone = TimeZone.UTC)
        shelfOnScreen()
        peerHolds()
        viewModel.events.test {
            awaitItem() // the peer's card was lifted

            viewModel.onAction(ShelfAction.OnCatchTap)

            // It lands at once, without waiting for the message to go out...
            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardCaught("9", 0.5f, 0.35f))
            // ...and goes again when it couldn't.
            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardWithdrawn("9"))
            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Network.DISCONNECTED))
            assertThat(viewModel.state.value.arriving).isEmpty()
        }
    }

    @Test
    fun `tapping the hint catches the card without the camera`() = runTest {
        shelfOnScreen(cameraAllowed = false)
        peerHolds()

        viewModel.onAction(ShelfAction.OnCatchTap)

        assertThat(peer.transport.sentSlides).containsExactly(SlideMessage.Released("9"))
        assertThat(peer.received.map { it.peerItemId }).containsExactly("9")
    }

    @Test
    fun `the peer putting its card back withdraws the offer`() = runTest {
        shelfOnScreen()
        peerHolds()
        viewModel.events.test {
            awaitItem()

            peer.transport.deliver(SlideMessage.DragCancel("9"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardWithdrawn("9"))
            assertThat(air.catchable).isNull()
        }
    }

    @Test
    fun `when both phones grabbed the same fist, the phone it was closer to keeps its grab`() = runTest {
        shelfOnScreen()
        holdCardOne()

        // The peer saw the fist bigger: this phone gives way.
        peerHolds(handSize = 0.6f)

        assertThat(air.heldId).isNull()
        assertThat(air.catchable).isEqualTo(CatchableCardUi("9", "River Valley.jpg"))
        assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.DragCancel("1"))
    }

    @Test
    fun `when this phone saw the fist bigger, it keeps its grab`() = runTest {
        shelfOnScreen()
        holdCardOne()

        peerHolds(handSize = 0.2f)

        assertThat(air.heldId).isEqualTo("1")
        assertThat(air.catchable).isNull()
    }

    // endregion

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }
}
