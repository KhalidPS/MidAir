package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferBoard
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferDirection
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKey
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferStatus
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
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.FakeShelfRepository.Companion.item
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ArrivingItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideFrame
import kotlinx.coroutines.CompletableDeferred
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

    private val keepAlive = RecordingKeepAlive()

    private fun createViewModel(withPeer: FakePeer) {
        peer = withPeer
        viewModel = ShelfViewModel(
            repository = repository,
            density = density,
            peer = peer,
            keepAlive = keepAlive,
            board = board,
            clock = clock,
            timeZone = TimeZone.UTC,
        )
    }

    private val board = TransferBoard()

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

    private fun commit(id: String) = SlideMessage.DragCommit(id, outwardMmPerS = 200f, alongEdgeMmPerS = 0f)

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
    fun `moves are stamped with when their frame was drawn`() = runTest {
        loaded()
        clock.nowMillis = 10_000
        viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))

        viewModel.onAction(ShelfAction.OnSlideMoved(frame().copy(frameTimeNanos = 2_000_000_000)))
        // Handled late, but drawn 8 ms after the first: stamped 8 ms after it.
        clock.nowMillis = 10_030
        viewModel.onAction(ShelfAction.OnSlideMoved(frame().copy(frameTimeNanos = 2_008_000_000)))

        val moves = peer.transport.sentSlides.filterIsInstance<SlideMessage.DragMove>()
        assertThat(moves.map { it.atMillis }).isEqualTo(listOf(10_000L, 10_008L))
    }

    @Test
    fun `on a slow link every other move is sent, and the last one always`() = runTest {
        createViewModel(FakePeer(media = FakeMediaChannel(isFast = false)))
        loaded()
        viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))

        repeat(6) { viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = -100f))) }
        viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = -500f)))

        val moves = peer.transport.sentSlides.filterIsInstance<SlideMessage.DragMove>()
        assertThat(moves.size).isEqualTo(4)
        assertThat(moves.last().penetrationMm).isCloseTo(-50f, Tolerance)
    }

    @Test
    fun `a shelf paired over a slow link says so`() = runTest {
        createViewModel(FakePeer(media = FakeMediaChannel(isFast = false)))

        viewModel.state.test {
            val state = awaitLoaded()
            assertThat(state.isPaired).isTrue()
            assertThat(state.isLinkSlow).isTrue()
        }
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

                assertThat(awaitItem()).isEqualTo(
                    ShelfEvent.ShowSendFailed(id = "1", name = "Q4 Report.pdf", error = DataError.Network.DISCONNECTED),
                )
            }
            assertThat(repository.items.value.map { it.id }).containsExactly("1")
            assertThat(expectMostRecentItem().items.map { it.id }).containsExactly("1")
        }
    }

    @Test
    fun `a sending file can be stopped from the list, and comes back to the shelf without an error`() = runTest {
        peer.sendGate = CompletableDeferred()
        viewModel.state.test {
            awaitLoaded()
            viewModel.events.test {
                viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
                viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = 200f)))
                viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.RIGHT))
                val sending = TransferKey(TransferDirection.SENDING, "1")
                assertThat(board.transfers.value.single().key).isEqualTo(sending)

                viewModel.onAction(ShelfAction.OnCancelTransfer(sending))
                assertThat(peer.cancelledSends).containsExactly("1")
                // The peer link answers a stopped send as cancelled.
                peer.sendResult = Result.Failure(DataError.Network.CANCELLED)
                peer.sendGate?.complete(Unit)

                expectNoEvents()
            }
            assertThat(board.transfers.value.single().status).isEqualTo(TransferStatus.CANCELLED)
            assertThat(repository.items.value.map { it.id }).containsExactly("1")
            assertThat(expectMostRecentItem().items.map { it.id }).containsExactly("1")
        }
    }

    @Test
    fun `retrying a failed send announces the card again and delivers the file`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnRetrySend("1"))

            val messages = peer.transport.sentSlides
            assertThat(messages.first()).isInstanceOf(SlideMessage.DragStart::class)
            assertThat(messages.last()).isEqualTo(SlideMessage.DragCommit("1", outwardMmPerS = 0f, alongEdgeMmPerS = 0f))
            assertThat(peer.sentFiles).containsExactly(OutgoingFile(itemId = "1", uri = "content://file/1"))
            assertThat(repository.items.value).isEmpty()
            assertThat(expectMostRecentItem().items).isEmpty()
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
    fun `the peer's card goes to the screen with its speed, when it was there, and the trip to predict away`() = runTest {
        viewModel.onAction(ShelfAction.OnViewportChanged(widthPx = 700, heightPx = 1500))
        clock.nowMillis = 1_080
        peer.transport.deliver(SlideMessage.Pong(pingSentAtMillis = 1_000)) // 80 ms round trip
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)

            peer.transport.deliver(move(penetrationMm = 20f, outwardMmPerS = 100f).copy(atMillis = 5_000))

            val moved = awaitItem() as ShelfEvent.PeerCardMoved
            // Where it was, not predicted: the screen plays the moves back evenly and predicts from there.
            assertThat(moved.leftPx).isCloseTo(-180f, Tolerance)
            // 100 mm/s outward through the peer's right edge is 100 mm/s rightward here.
            assertThat(moved.velocityXPxPerS).isCloseTo(1_000f, Tolerance)
            assertThat(moved.sentAtMillis).isEqualTo(5_000L)
            assertThat(moved.leadMillis).isCloseTo(40f, Tolerance)
        }
    }

    @Test
    fun `the peer's card is drawn in this screen's millimetres`() = runTest {
        // The peer's millimetres are 10 % longer than this screen's.
        createViewModel(FakePeer(link = SlideLink(gapMm = 8f, peerSide = ExitEdge.RIGHT, peerMmScale = 1.1f)))
        viewModel.events.test {
            peer.transport.deliver(peerStart)

            val lifted = awaitItem() as ShelfEvent.PeerCardLifted
            assertThat(lifted.card.widthPx).isCloseTo(330f, Tolerance)
            assertThat(lifted.card.heightPx).isCloseTo(440f, Tolerance)
        }
    }

    @Test
    fun `a card let go on the peer arrives with its speed`() = runTest {
        viewModel.onAction(ShelfAction.OnViewportChanged(widthPx = 700, heightPx = 1500))
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)
            peer.transport.deliver(move())
            skipItems(1)
            peer.transport.deliver(commit("9"))

            val arrived = awaitItem() as ShelfEvent.PeerCardArrived
            // 200 mm/s out of the peer's right edge: 2 000 px/s rightward on this screen.
            assertThat(arrived.velocityXPxPerS).isCloseTo(2_000f, Tolerance)
            cancelAndIgnoreRemainingEvents()
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
    fun `a card let go on the peer waits in its slot, then becomes the received item in place`() = runTest {
        val arrival = CompletableDeferred<Result<String, DataError>>()
        createViewModel(FakePeer(onReceive = { arrival.await() }))
        viewModel.state.test {
            awaitLoaded()
            viewModel.events.test {
                peer.transport.deliver(peerStart)
                skipItems(1)

                peer.transport.deliver(commit("9"))

                assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardArrived("9"))
            }
            // The 30 × 40 mm card keeps its shape in its slot.
            assertThat(expectMostRecentItem().arriving)
                .containsExactly(ArrivingItemUi(id = "9", name = "River Valley.jpg", aspectRatio = 0.75f))
            assertThat(peer.received).containsExactly(
                IncomingFile(peerItemId = "9", name = "River Valley.jpg", mimeType = "image/jpeg", sizeBytes = 395_000),
            )

            // On the shelf before the transfer is done: held back, so it never shows next to its card.
            repository.items.update { items -> listOf(item("received-1", name = "River Valley.jpg")) + items }
            expectNoEvents()

            arrival.complete(Result.Success("received-1"))

            val received = expectMostRecentItem()
            assertThat(received.arriving).isEmpty()
            assertThat(received.items.map { it.id }).containsExactly("received-1", "1")
            assertThat(received.arrivedFrom).isEqualTo(mapOf("received-1" to "9"))
        }
    }

    @Test
    fun `the arriving card's transfer shows how much of its file is here, apart from the shelf's state`() = runTest {
        val arrival = CompletableDeferred<Result<String, DataError>>()
        createViewModel(FakePeer(onReceive = { arrival.await() }, progressSteps = listOf(98_750L, 197_500L)))
        viewModel.state.test {
            awaitLoaded()
            peer.transport.deliver(peerStart)
            peer.transport.deliver(commit("9"))

            // The shelf only knows the card is waiting; how far its file has got is the transfer's.
            assertThat(expectMostRecentItem().arriving.single())
                .isEqualTo(ArrivingItemUi(id = "9", name = "River Valley.jpg", aspectRatio = 0.75f))
        }
        val transfer = board.transfers.value.single()
        assertThat(transfer.key).isEqualTo(TransferKey(TransferDirection.RECEIVING, "9"))
        assertThat(transfer.status).isEqualTo(TransferStatus.ACTIVE)
        assertThat(transfer.fraction).isEqualTo(0.5f)
    }

    @Test
    fun `a received file's transfer ends as done, a failed one as failed`() = runTest {
        val arrivals = mutableMapOf<String, CompletableDeferred<Result<String, DataError>>>()
        createViewModel(
            FakePeer(onReceive = { file ->
                CompletableDeferred<Result<String, DataError>>().also { arrivals[file.peerItemId] = it }.await()
            }),
        )
        viewModel.state.test {
            awaitLoaded()
            peer.transport.deliver(peerStart)
            peer.transport.deliver(commit("9"))
            peer.transport.deliver(peerStart.copy(itemId = "10", name = "Notes.md"))
            peer.transport.deliver(commit("10"))

            arrivals.getValue("9").complete(Result.Success("received-1"))
            arrivals.getValue("10").complete(Result.Failure(DataError.Network.DISCONNECTED))
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(board.transfers.value.map { it.key.itemId to it.status }).containsExactly(
            "9" to TransferStatus.DONE,
            "10" to TransferStatus.FAILED,
        )
    }

    @Test
    fun `stopping an arriving card's transfer from the list stops its receive`() = runTest {
        val arrival = CompletableDeferred<Result<String, DataError>>()
        createViewModel(FakePeer(onReceive = { arrival.await() }))
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            peer.transport.deliver(commit("9"))
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.onAction(ShelfAction.OnCancelTransfer(TransferKey(TransferDirection.RECEIVING, "9")))

        assertThat(peer.cancelledReceives).containsExactly("9")
    }

    @Test
    fun `the transfers list opens and closes`() = runTest {
        createViewModel(FakePeer())
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnTransfersClick)
            assertThat(awaitItem().isTransfersVisible).isEqualTo(true)
            viewModel.onAction(ShelfAction.OnTransfersDismiss)
            assertThat(awaitItem().isTransfersVisible).isEqualTo(false)
        }
    }

    @Test
    fun `cards keep arriving while earlier files are still coming in`() = runTest {
        val arrivals = mutableMapOf<String, CompletableDeferred<Result<String, DataError>>>()
        createViewModel(
            FakePeer(onReceive = { file ->
                CompletableDeferred<Result<String, DataError>>().also { arrivals[file.peerItemId] = it }.await()
            }),
        )
        viewModel.state.test {
            awaitLoaded()
            peer.transport.deliver(peerStart)
            peer.transport.deliver(commit("9"))
            peer.transport.deliver(peerStart.copy(itemId = "10", name = "Notes.md"))
            peer.transport.deliver(commit("10"))

            assertThat(expectMostRecentItem().arriving.map { it.id }).containsExactly("10", "9")

            arrivals.getValue("9").complete(Result.Failure(DataError.Network.DISCONNECTED))

            assertThat(expectMostRecentItem().arriving.map { it.id }).containsExactly("10")
        }
    }

    @Test
    fun `a tap stops only a file that is coming in, and a stopped transfer withdraws quietly`() = runTest {
        val arrival = CompletableDeferred<Result<String, DataError>>()
        createViewModel(FakePeer(onReceive = { arrival.await() }))
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)
            viewModel.onAction(ShelfAction.OnIncomingCardTap("9"))
            assertThat(peer.cancelledReceives).isEmpty()

            peer.transport.deliver(commit("9"))
            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardArrived("9"))
            viewModel.onAction(ShelfAction.OnIncomingCardTap("9"))
            assertThat(peer.cancelledReceives).containsExactly("9")

            arrival.complete(Result.Failure(DataError.Network.CANCELLED))
            assertThat(awaitItem()).isEqualTo(ShelfEvent.PeerCardWithdrawn("9"))
            expectNoEvents()
        }
    }

    @Test
    fun `the app is kept alive while a handed-over file is sent, and let go after`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnSlideLifted("1", 300f, 400f, null))
        viewModel.onAction(ShelfAction.OnSlideMoved(frame(penetrationPx = 200f)))

        viewModel.onAction(ShelfAction.OnItemSent("1", ExitEdge.RIGHT))

        assertThat(keepAlive.descriptions).containsExactly("Sending “Q4 Report.pdf”")
        assertThat(keepAlive.held).isEqualTo(0)
    }

    @Test
    fun `while a file comes in, the app is kept alive and messages keep flowing`() = runTest {
        val arrival = CompletableDeferred<Result<String, DataError>>()
        createViewModel(FakePeer(onReceive = { arrival.await() }))
        peer.transport.deliver(peerStart)
        peer.transport.deliver(commit("9"))

        assertThat(keepAlive.held).isEqualTo(1)
        assertThat(keepAlive.descriptions).containsExactly("Receiving “River Valley.jpg”")
        // Not stuck behind the transfer: a probe still gets its answer.
        peer.transport.deliver(SlideMessage.Ping(sentAtMillis = 7))
        assertThat(peer.transport.sent.last()).isEqualTo(SlideMessage.Pong(pingSentAtMillis = 7))

        arrival.complete(Result.Failure(DataError.Network.CANCELLED))
        assertThat(keepAlive.held).isEqualTo(0)
    }

    @Test
    fun `a card that can't be received is withdrawn and the error shown`() = runTest {
        createViewModel(FakePeer(onReceive = { Result.Failure(DataError.Local.DISK_FULL) }))
        viewModel.events.test {
            peer.transport.deliver(peerStart)
            skipItems(1)

            peer.transport.deliver(commit("9"))

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

    private class RecordingKeepAlive : TransferKeepAlive {
        var held = 0
        val descriptions = mutableListOf<String>()

        override fun hold(description: String): () -> Unit {
            held++
            descriptions += description
            return { held-- }
        }
    }

    private class MutableClock : Clock {
        var nowMillis = 0L
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }
}

/** Pixel/millimetre conversions go through floats; a hundredth is far below anything visible. */
private const val Tolerance = 0.01f

private const val ScreenWidthPx = 700f
