package com.kotlistine.transferingfilebyair.feature.pairing.presentation.lineup

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.EdgeCrossing
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LineUpViewModelTest {

    private val link = FakeLink()
    private val geometry = FakeGeometry()
    private val clock = MutableClock()
    private lateinit var viewModel: LineUpViewModel

    /** The finger leaves this phone through its right edge, 50 mm down, at 200 mm/s (10 px per mm). */
    private val leavingRight = SwipeCrossingPx(
        leaving = true,
        edge = ExitEdge.RIGHT,
        alongEdgePx = 500f,
        millisFromLast = 0,
        acrossPxPerS = 2_000f,
        alongEdgePxPerS = 0f,
    )

    /** The other phone felt it come in 50 ms later (by its clock, 300 ms ahead), 55 mm down. */
    private val arrivedOnTheOther = EdgeCrossing(
        leaving = false,
        edge = ExitEdge.LEFT,
        alongEdgeMm = 55f,
        atMillis = 1_350,
        acrossMmPerS = 200f,
        alongEdgeMmPerS = 0f,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        clock.nowMillis = 1_000
        viewModel = LineUpViewModel(link, geometry, ScreenDensity(xdpi = 254f, ydpi = 254f), clock)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The other phone answered a probe at once, its clock 300 ms ahead. */
    private suspend fun clocksCompared() =
        link.inbound.emit(SlideMessage.Pong(pingSentAtMillis = 1_000, answeredAtMillis = 1_300))

    @Test
    fun `opening it compares the two phones' clocks`() = runTest {
        advanceUntilIdle()

        assertThat(link.sent.filterIsInstance<SlideMessage.Ping>()).hasSize(10)
    }

    @Test
    fun `a swipe from this phone onto the other lines the screens up`() = runTest {
        viewModel.events.test {
            clocksCompared()

            viewModel.onAction(LineUpAction.OnEdgeCrossed(leavingRight))
            assertThat(viewModel.state.value.phase).isEqualTo(LineUpPhase.MEASURING)
            link.inbound.emit(SlideMessage.SwipeCrossed(arrivedOnTheOther))

            assertThat(awaitItem()).isEqualTo(LineUpEvent.LinedUp)
        }
        val lined = viewModel.state.value
        assertThat(lined.phase).isEqualTo(LineUpPhase.LINED_UP)
        assertThat(lined.peerSide).isEqualTo(ExitEdge.RIGHT)
        assertThat(lined.gapMm!!).isCloseTo(10f, 0.01f)
        // The same point is 5 mm further down over there: its screen sits higher.
        assertThat(lined.peerLowerByMm!!).isCloseTo(-5f, 0.01f)
        assertThat(geometry.link.value.peerSide).isEqualTo(ExitEdge.RIGHT)
        assertThat(geometry.link.value.gapMm).isCloseTo(10f, 0.01f)
        assertThat(link.sent.filterIsInstance<SlideMessage.SwipeCrossed>().map { it.crossing }).containsExactly(
            EdgeCrossing(leaving = true, edge = ExitEdge.RIGHT, alongEdgeMm = 50f, atMillis = 1_000, acrossMmPerS = 200f, alongEdgeMmPerS = 0f),
        )
    }

    @Test
    fun `either phone's half can come first`() = runTest {
        clocksCompared()
        link.inbound.emit(SlideMessage.SwipeCrossed(arrivedOnTheOther))

        viewModel.onAction(LineUpAction.OnEdgeCrossed(leavingRight))

        assertThat(viewModel.state.value.phase).isEqualTo(LineUpPhase.LINED_UP)
    }

    @Test
    fun `half a swipe the other phone never felt is missed, and changes nothing`() = runTest {
        viewModel.onAction(LineUpAction.OnEdgeCrossed(leavingRight))

        advanceTimeBy(MissAfterMillis + 1)

        assertThat(viewModel.state.value.phase).isEqualTo(LineUpPhase.MISSED)
        assertThat(geometry.link.value).isEqualTo(SlideLink())
    }

    @Test
    fun `two halves that can't be one swipe across are missed`() = runTest {
        clocksCompared()
        viewModel.onAction(LineUpAction.OnEdgeCrossed(leavingRight))

        link.inbound.emit(SlideMessage.SwipeCrossed(arrivedOnTheOther.copy(leaving = true, edge = ExitEdge.RIGHT)))

        assertThat(viewModel.state.value.phase).isEqualTo(LineUpPhase.MISSED)
        assertThat(geometry.link.value).isInstanceOf(SlideLink::class)
        assertThat(geometry.link.value).isEqualTo(SlideLink())
    }

    @Test
    fun `it names the phone it lines up with`() {
        assertThat(viewModel.state.value.peerName).isEqualTo("Khalid's Pixel")
    }

    private class FakeLink : PeerLink {
        val sent = mutableListOf<SlideMessage>()
        val inbound = MutableSharedFlow<SlideMessage>()
        override val state = MutableStateFlow<LinkState>(LinkState.Connected(PeerDevice("p1", "Khalid's Pixel")))

        override val transport: Transport = object : Transport {
            override val incoming: Flow<SlideMessage> = inbound
            override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
                sent += message
                return Result.Success(Unit)
            }
        }

        override suspend fun startSearching(): EmptyResult<DataError.Network> = Result.Success(Unit)
        override fun stopSearching() = Unit
        override suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network> = Result.Success(Unit)
        override suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network> = Result.Success(Unit)
        override fun decline(peer: PeerDevice) = Unit
        override fun disconnect() = Unit
        override suspend fun sendFile(uri: String, announce: suspend (transferId: Long) -> Unit): EmptyResult<DataError.Network> =
            Result.Failure(DataError.Network.DISCONNECTED)
        override suspend fun receiveFile(transferId: Long, onProgress: (Float) -> Unit): Result<String, DataError.Network> =
            Result.Failure(DataError.Network.DISCONNECTED)
        override fun cancelTransfer(transferId: Long) = Unit
    }

    private class FakeGeometry : LinkGeometry {
        override val link = MutableStateFlow(SlideLink())
        override fun update(link: SlideLink) {
            this.link.value = link
        }
    }

    private class MutableClock : Clock {
        var nowMillis = 0L
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }
}
