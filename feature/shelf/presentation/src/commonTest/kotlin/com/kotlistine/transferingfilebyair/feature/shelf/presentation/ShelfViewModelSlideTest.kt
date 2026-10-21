package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.FakeShelfRepository.Companion.item
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

/** Slides with a linked device: mirroring a carried card to the peer, and receiving the peer's cards. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelSlideTest {

    private lateinit var repository: FakeShelfRepository
    private lateinit var peer: FakePeer
    private lateinit var viewModel: ShelfViewModel
    private val clock = MutableClock()

    /** 254 dpi: exactly 10 px per millimetre, so the expected numbers read easily. */
    private val density = ScreenDensity(xdpi = 254f, ydpi = 254f)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeShelfRepository()
        repository.items.value = listOf(item("1", name = "Q4 Report.pdf"))
        createViewModel(FakePeer())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(withPeer: FakePeer) {
        peer = withPeer
        viewModel = ShelfViewModel(repository, density, peer, clock, TimeZone.UTC)
    }

    /** A 300 px wide card on a 700 px wide screen, its right side [penetrationPx] past the right edge. */
    private fun frame(penetrationPx: Float = -100f, cardTopPx: Float = 250f, cardWidthPx: Float = 300f): SlideFrame {
        val right = ScreenWidthPx + penetrationPx
        return SlideFrame(
            id = "1",
            cardLeftPx = right - cardWidthPx,
            cardTopPx = cardTopPx,
            cardRightPx = right,
            screenWidthPx = ScreenWidthPx,
            velocityXPxPerS = 900f,
            velocityYPxPerS = -30f,
            tiltDegrees = 3f,
        )
    }

    /** The same card leaving through the left edge, [penetrationPx] past it. */
    private fun leftFrame(penetrationPx: Float) = SlideFrame(
        id = "1",
        cardLeftPx = -penetrationPx,
        cardTopPx = 250f,
        cardRightPx = -penetrationPx + 300f,
        screenWidthPx = ScreenWidthPx,
        velocityXPxPerS = -900f,
        velocityYPxPerS = 0f,
        tiltDegrees = 0f,
    )

    private fun move(seq: Int = 1, penetrationMm: Float = 20f, outwardMmPerS: Float = 0f) = SlideMessage.DragMove(
        itemId = "9",
        seq = seq,
        exitEdge = ExitEdge.RIGHT,
        alongEdgeMm = 30f,
        penetrationMm = penetrationMm,
        outwardMmPerS = outwardMmPerS,
        alongEdgeMmPerS = 0f,
        tiltDegrees = 2f,
    )

    private val peerStart = SlideMessage.DragStart(
        itemId = "9",
        name = "River Valley.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 395_000,
        cardWidthMm = 30f,
        cardHeightMm = 40f,
        thumbnail = null,
    )

    private suspend fun loaded() = viewModel.state.test { awaitLoaded() }

    // region Sending

    @Test
    fun `lifting a card tells the peer what is coming, sized in millimetres`() = runTest {
        loaded()
        val thumbnail = byteArrayOf(1, 2, 3)

        viewModel.onAction(ShelfAction.OnSlideLifted("1", cardWidthPx = 300f, cardHeightPx = 400f, thumbnail))

        val start = peer.transport.sentSlides.single() as SlideMessage.DragStart
        assertThat(start.itemId).isEqualTo("1")
        assertThat(start.name).isEqualTo("Q4 Report.pdf")
        assertThat(start.mimeType).isEqualTo("application/pdf")
        assertThat(start.sizeBytes).isEqualTo(16_000)
        assertThat(start.cardWidthMm).isCloseTo(30f, Tolerance)
        assertThat(start.cardHeightMm).isCloseTo(40f, Tolerance)
        assertThat(start.thumbnail).isSameInstanceAs(thumbnail)
    }

    @Test
    fun `moves stream only once the card is near the peer's edge`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))

        viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = -500f)))
        viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = -100f)))

        val moves = peer.transport.sentSlides.filterIsInstance<SlideMessage.DragMove>()
        assertThat(moves.size).isEqualTo(1)
        val move = moves.single()
        assertThat(move.seq).isEqualTo(1)
        assertThat(move.exitEdge).isEqualTo(ExitEdge.RIGHT)
        assertThat(move.alongEdgeMm).isCloseTo(25f, Tolerance)
        assertThat(move.penetrationMm).isCloseTo(-10f, Tolerance)
        assertThat(move.outwardMmPerS).isCloseTo(90f, Tolerance)
        assertThat(move.alongEdgeMmPerS).isCloseTo(-3f, Tolerance)
        assertThat(move.tiltDegrees).isEqualTo(3f)
    }

    @Test
    fun `moves toward an edge that doesn't face the peer are not streamed`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))

        viewModel.onAction(ShelfAction.OnSlideMoved(leftFrame(penetrationPx = 50f)))

        assertThat(peer.transport.sentSlides.filterIsInstance<SlideMessage.DragMove>()).isEmpty()
    }

    @Test
    fun `a card that springs back is withdrawn from the peer`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
        viewModel.onAction(ShelfAction.OnSlideMoved(frame()))

        viewModel.onAction(ShelfAction.OnSlideReturned("1"))

        assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.DragCancel("1"))
    }

    @Test
    fun `a card sent through the peer's edge is handed over and leaves the shelf for good`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
            viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = 200f)))

            viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.RIGHT))

            val commit = peer.transport.sentSlides.last() as SlideMessage.DragCommit
            assertThat(commit.itemId).isEqualTo("1")
            assertThat(commit.outwardMmPerS).isCloseTo(90f, Tolerance)
            assertThat(peer.sentFiles).containsExactly(OutgoingFile(itemId = "1", uri = "content://file/1"))
            assertThat(repository.items.value).isEmpty()
            assertThat(expectMostRecentItem().items).isEmpty()
        }
    }

    @Test
    fun `a card sent through the other edge is not handed over and can still be undone`() = runTest {
        loaded()
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
            viewModel.onAction(ShelfAction.OnSlideMoved(leftFrame(penetrationPx = 200f)))

            viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.LEFT))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowSent(id = "1", name = "Q4 Report.pdf"))
            assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.DragCancel("1"))
            assertThat(repository.items.value.map { it.id }).containsExactly("1")
        }
    }

    @Test
    fun `a card flung out the other side after nearing the peer is not handed over`() = runTest {
        loaded()
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
            viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = 50f)))

            viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.LEFT))

            assertThat(awaitItem()).isInstanceOf(ShelfEvent.ShowSent::class)
            assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.DragCancel("1"))
        }
    }

    @Test
    fun `a card whose start didn't reach the peer stays undoable`() = runTest {
        peer.transport.failWith = { if (it is SlideMessage.DragStart) DataError.Network.PAYLOAD_TOO_LARGE else null }
        loaded()
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
            viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = 200f)))

            viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.RIGHT))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowSent(id = "1", name = "Q4 Report.pdf"))
            assertThat(repository.items.value.map { it.id }).containsExactly("1")
        }
    }

    @Test
    fun `a start too big for one message goes again without the picture, and the card is still handed over`() = runTest {
        peer.transport.failWith = { message ->
            DataError.Network.PAYLOAD_TOO_LARGE.takeIf { message is SlideMessage.DragStart && message.thumbnail != null }
        }
        loaded()
        viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, thumbnail = ByteArray(10)))
        viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = 200f)))

        viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.RIGHT))

        val start = peer.transport.sentSlides.first() as SlideMessage.DragStart
        assertThat(start.thumbnail).isNull()
        assertThat(peer.transport.sentSlides.last()).isInstanceOf(SlideMessage.DragCommit::class)
        assertThat(repository.items.value).isEmpty()
    }

    @Test
    fun `a file that fails to reach the peer stays on the shelf, with the error`() = runTest {
        peer.sendResult = Result.Failure(DataError.Network.DISCONNECTED)
        viewModel.state.test {
            awaitLoaded()
            viewModel.events.test {
                viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
                viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = 200f)))

                viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.RIGHT))

                assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Network.DISCONNECTED))
            }
            assertThat(repository.items.value.map { it.id }).containsExactly("1")
            assertThat(expectMostRecentItem().items.map { it.id }).containsExactly("1")
        }
    }

    @Test
    fun `a hand-off the link refuses stays undoable`() = runTest {
        peer.transport.failWith = { if (it is SlideMessage.DragCommit) DataError.Network.DISCONNECTED else null }
        loaded()
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
            viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = 200f)))

            viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.RIGHT))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowSent(id = "1", name = "Q4 Report.pdf"))
            assertThat(repository.items.value.map { it.id }).containsExactly("1")
        }
    }

    @Test
    fun `with the peer's side unknown, a card dragged back past the middle is still reported from its edge`() = runTest {
        createViewModel(FakePeer(link = SlideLink(gapMm = 8f, peerSide = null)))
        loaded()
        viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
        viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = -100f)))

        // Centre now left of the middle (nearer the left edge), right side still 21 mm from the right edge.
        viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = -210f, cardWidthPx = 400f)))

        val last = peer.transport.sentSlides.last() as SlideMessage.DragMove
        assertThat(last.exitEdge).isEqualTo(ExitEdge.RIGHT)
        assertThat(last.penetrationMm).isCloseTo(-21f, Tolerance)
    }

    @Test
    fun `the peer's round-trip probes are answered`() = runTest {
        peer.transport.deliver(SlideMessage.Ping(sentAtMillis = 42))

        assertThat(peer.transport.sent.last()).isEqualTo(SlideMessage.Pong(pingSentAtMillis = 42))
    }

    // endregion

    // region Receiving

    @Test
    fun `a card lifted on the peer is announced at the same physical size`() = runTest {
        viewModel.events.test {
            peer.transport.deliver(peerStart)

            val lifted = awaitItem() as ShelfEvent.PeerCardLifted
            assertThat(lifted.card.id).isEqualTo("9")
            assertThat(lifted.card.widthPx).isCloseTo(300f, Tolerance)
            assertThat(lifted.card.heightPx).isCloseTo(400f, Tolerance)
        }
    }

    @Test
    fun `the peer's card is placed so it continues across the gap`() = runTest {
        viewModel.onAction(ShelfAction.OnViewportChanged(widthPx = 700, heightPx = 1500))
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)

            // 20 mm past the peer's edge, 8 mm of gap: 12 mm of the 30 mm card shows at this screen's left edge.
            peer.transport.deliver(move(penetrationMm = 20f))

            val moved = awaitItem() as ShelfEvent.PeerCardMoved
            assertThat(moved.id).isEqualTo("9")
            assertThat(moved.leftPx).isCloseTo(-180f, Tolerance)
            assertThat(moved.topPx).isCloseTo(300f, Tolerance)
            assertThat(moved.tiltDegrees).isEqualTo(2f)
            assertThat(moved.arrival).isEqualTo(1f)
            assertThat(moved.entryEdge).isEqualTo(ExitEdge.LEFT)
        }
    }

    @Test
    fun `the peer's card is drawn ahead by half the measured round trip`() = runTest {
        viewModel.onAction(ShelfAction.OnViewportChanged(widthPx = 700, heightPx = 1500))
        clock.nowMillis = 1_080
        peer.transport.deliver(SlideMessage.Pong(pingSentAtMillis = 1_000)) // 80 ms round trip
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)

            // 100 mm/s outward, predicted 40 ms (half the round trip) + 30 ms (smoothing) ahead: 7 mm further in.
            peer.transport.deliver(move(penetrationMm = 20f, outwardMmPerS = 100f))

            val moved = awaitItem() as ShelfEvent.PeerCardMoved
            assertThat(moved.leftPx).isCloseTo(-110f, Tolerance)
        }
    }

    @Test
    fun `moves that arrive out of order are dropped`() = runTest {
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)

            peer.transport.deliver(move(seq = 2))
            peer.transport.deliver(move(seq = 1))

            assertThat(awaitItem()).isInstanceOf(ShelfEvent.PeerCardMoved::class)
            expectNoEvents()
        }
    }

    @Test
    fun `a card the peer keeps is withdrawn`() = runTest {
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)

            peer.transport.deliver(SlideMessage.DragCancel("9"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardWithdrawn("9"))
        }
    }

    @Test
    fun `a card let go on the peer lands on this shelf`() = runTest {
        createViewModel(
            FakePeer(onReceive = {
                repository.items.update { items -> listOf(item("received-1")) + items }
                Result.Success("received-1")
            }),
        )
        viewModel.state.test {
            awaitLoaded()
            viewModel.events.test {
                peer.transport.deliver(peerStart)
                skipItems(1)

                peer.transport.deliver(SlideMessage.DragCommit("9", outwardMmPerS = 200f, alongEdgeMmPerS = 0f))

                assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardArrived("9"))
            }
            assertThat(peer.received).containsExactly(
                IncomingFile(peerItemId = "9", name = "River Valley.jpg", mimeType = "image/jpeg", sizeBytes = 395_000),
            )
            val landing = expectMostRecentItem()
            assertThat(landing.landingItemId).isEqualTo("received-1")
            assertThat(landing.items.map { it.id }).containsExactly("received-1", "1")

            viewModel.onAction(ShelfAction.OnLandingFinished)
            assertThat(awaitItem().landingItemId).isNull()
        }
    }

    @Test
    fun `a card that can't be received is withdrawn and the error shown`() = runTest {
        createViewModel(FakePeer(onReceive = { Result.Failure(DataError.Local.DISK_FULL) }))
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)

            peer.transport.deliver(SlideMessage.DragCommit("9", outwardMmPerS = 200f, alongEdgeMmPerS = 0f))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardArrived("9"))
            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardWithdrawn("9"))
            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Local.DISK_FULL))
        }
    }

    @Test
    fun `a paired shelf says so`() = runTest {
        viewModel.state.test {
            assertThat(awaitLoaded().isPaired).isTrue()
        }
    }

    // endregion

    private suspend fun ReceiveTurbine<ShelfState>.awaitLoaded(): ShelfState {
        var state = awaitItem()
        while (state.isLoading) state = awaitItem()
        return state
    }

    private class MutableClock : Clock {
        var nowMillis = 0L
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }
}

/** Pixel/millimetre conversions go through floats; a hundredth is far below anything visible. */
private const val Tolerance = 0.01f

private const val ScreenWidthPx = 700f
