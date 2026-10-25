package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.prop
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.media.VideoSource
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.FakeShelfRepository.Companion.item
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.PlayingVideoUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
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

/** A video playing on one phone, carried on a card to the other, keeps playing there. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelLiveVideoTest {

    private lateinit var repository: FakeShelfRepository
    private lateinit var engine: FakeVideoEngine
    private lateinit var media: FakeMediaChannel
    private lateinit var peer: FakePeer
    private lateinit var viewModel: ShelfViewModel
    private val clock = MutableClock()

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
        createViewModel()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(onReceive: suspend () -> Result<String, DataError> = { CompletableDeferred<Nothing>().await() }) {
        peer = FakePeer(onReceive = { onReceive() }, media = media)
        viewModel = ShelfViewModel(
            repository = repository,
            density = ScreenDensity(xdpi = 254f, ydpi = 254f),
            peer = peer,
            videoEngine = engine,
            clock = clock,
            timeZone = TimeZone.UTC,
        )
    }

    private suspend fun loaded() = viewModel.state.test { awaitLoaded() }

    private fun lift(id: String) = viewModel.onAction(ShelfAction.OnSlideLifted(id, 300f, 190f, null))

    // region Sending

    @Test
    fun `lifting a playing video offers it to the peer right behind the card`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))
        engine.positionMs = 12_000

        lift("1")

        assertThat(peer.transport.sentSlides[1])
            .isEqualTo(SlideMessage.PlaybackOffer("1", sizeBytes = 16_000, positionMs = 12_000, isPlaying = true))
        assertThat(media.shared).isEqualTo(mapOf("1" to "content://videos/1"))
        viewModel.onAction(ShelfAction.OnSlideReturned("1"))
    }

    @Test
    fun `a paused video is offered paused`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))

        lift("1")

        assertThat(peer.transport.sentSlides[1]).isInstanceOf(SlideMessage.PlaybackOffer::class)
            .prop(SlideMessage.PlaybackOffer::isPlaying).isEqualTo(false)
        viewModel.onAction(ShelfAction.OnSlideReturned("1"))
    }

    @Test
    fun `while the card is in the air the peer hears where the video is`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))
        lift("1")
        engine.positionMs = 12_250

        advanceTimeBy(260)

        assertThat(peer.transport.sentSlides.last()).isEqualTo(SlideMessage.PlaybackClock("1", 12_250, isPlaying = true))
        viewModel.onAction(ShelfAction.OnSlideReturned("1"))
    }

    @Test
    fun `a video isn't offered while the link is still too slow`() = runTest {
        media.isFast = false
        loaded()
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))

        lift("1")

        assertThat(peer.transport.sentSlides.filterIsInstance<SlideMessage.PlaybackOffer>()).isEmpty()
        assertThat(media.shared).isEqualTo(emptyMap())
    }

    @Test
    fun `a card that isn't the playing video is lifted as before`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))

        lift("2")

        assertThat(peer.transport.sentSlides.single()).isInstanceOf(SlideMessage.DragStart::class)
    }

    @Test
    fun `a video that springs back stops being readable by the peer`() = runTest {
        loaded()
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))
        lift("1")

        viewModel.onAction(ShelfAction.OnSlideReturned("1"))

        assertThat(media.shared).isEqualTo(emptyMap())
    }

    // endregion

    // region Receiving

    private val peerStart = SlideMessage.DragStart(
        itemId = "9",
        name = "Climb.mp4",
        mimeType = "video/mp4",
        sizeBytes = 24_000_000,
        cardWidthMm = 40f,
        cardHeightMm = 25f,
        thumbnail = null,
    )

    private val peerOffer = SlideMessage.PlaybackOffer("9", sizeBytes = 24_000_000, positionMs = 5_000, isPlaying = true)

    private suspend fun receiveOffer() {
        peer.transport.deliver(peerStart)
        peer.transport.deliver(peerOffer)
    }

    @Test
    fun `an offered video plays along with the incoming card, muted, from the peer's frame`() = runTest {
        loaded()

        receiveOffer()

        val source = engine.loaded as VideoSource.Remote
        assertThat(source.id).isEqualTo("9")
        assertThat(source.sizeBytes).isEqualTo(24_000_000)
        // Ahead of the reported frame, by the time it takes to start.
        assertThat(engine.loadedAtMs).isEqualTo(6_500)
        assertThat(engine.currentVolume).isEqualTo(0f)
        assertThat(viewModel.state.value.playing).isEqualTo(PlayingVideoUi("9", isPaused = false))
    }

    @Test
    fun `the video is read from the peer as the player asks for it`() = runTest {
        loaded()
        receiveOffer()

        (engine.loaded as VideoSource.Remote).open(1_234)

        assertThat(media.opened).containsExactly("9" to 1_234L)
    }

    @Test
    fun `an offer for a card that isn't coming in is ignored`() = runTest {
        loaded()

        peer.transport.deliver(peerOffer)

        assertThat(engine.loaded).isNull()
        assertThat(viewModel.state.value.playing).isNull()
    }

    @Test
    fun `the peer's reports keep this half on the same frame`() = runTest {
        loaded()
        receiveOffer()
        clock.nowMillis = 1_500
        engine.isPlaying = true
        engine.positionMs = 6_400

        peer.transport.deliver(SlideMessage.PlaybackClock("9", positionMs = 6_500, isPlaying = true))

        // 100 ms behind: it catches up by playing a little faster.
        assertThat(engine.currentSpeed).isEqualTo(1.05f)
    }

    @Test
    fun `arriving ahead of the peer it holds its frame, then plays on`() = runTest {
        loaded()
        receiveOffer()
        clock.nowMillis = 1_500
        engine.isPlaying = true
        engine.positionMs = 6_900

        peer.transport.deliver(SlideMessage.PlaybackClock("9", positionMs = 6_500, isPlaying = true))

        assertThat(engine.playWhenReady.value).isEqualTo(false)
        advanceTimeBy(401)
        assertThat(engine.playWhenReady.value).isEqualTo(true)
    }

    @Test
    fun `while the video is still buffering it isn't corrected`() = runTest {
        loaded()
        receiveOffer()
        clock.nowMillis = 1_500
        engine.positionMs = 6_500

        peer.transport.deliver(SlideMessage.PlaybackClock("9", positionMs = 6_500, isPlaying = true))

        assertThat(engine.seeks).isEmpty()
        assertThat(engine.currentSpeed).isEqualTo(1f)
    }

    @Test
    fun `when the peer pauses its video, this half pauses too`() = runTest {
        loaded()
        receiveOffer()

        peer.transport.deliver(SlideMessage.PlaybackClock("9", positionMs = 5_200, isPlaying = false))

        assertThat(viewModel.state.value.playing).isEqualTo(PlayingVideoUi("9", isPaused = true))
    }

    @Test
    fun `letting the card go here turns the sound on and plays on`() = runTest {
        loaded()
        receiveOffer()
        engine.setSpeed(1.1f)

        peer.transport.deliver(SlideMessage.DragCommit("9", outwardMmPerS = 200f, alongEdgeMmPerS = 0f))

        assertThat(engine.currentVolume).isEqualTo(1f)
        assertThat(engine.currentSpeed).isEqualTo(1f)
        assertThat(viewModel.state.value.playing).isEqualTo(PlayingVideoUi("9", isPaused = false))
        assertThat(engine.releaseCount).isEqualTo(0)
    }

    @Test
    fun `reports after the card was let go here no longer steer the video`() = runTest {
        loaded()
        receiveOffer()
        peer.transport.deliver(SlideMessage.DragCommit("9", outwardMmPerS = 200f, alongEdgeMmPerS = 0f))
        clock.nowMillis = 1_500
        engine.isPlaying = true

        peer.transport.deliver(SlideMessage.PlaybackClock("9", positionMs = 9_000, isPlaying = true))

        assertThat(engine.seeks).isEmpty()
    }

    @Test
    fun `a card the peer takes back stops its video here`() = runTest {
        loaded()
        receiveOffer()

        peer.transport.deliver(SlideMessage.DragCancel("9"))

        assertThat(viewModel.state.value.playing).isNull()
        assertThat(engine.releaseCount).isEqualTo(1)
    }

    @Test
    fun `the received item plays on where its arriving card did`() = runTest {
        createViewModel(
            onReceive = {
                repository.items.update { listOf(item("received-1", name = "Climb.mp4", mimeType = "video/mp4")) + it }
                Result.Success("received-1")
            },
        )
        loaded()
        receiveOffer()

        peer.transport.deliver(SlideMessage.DragCommit("9", outwardMmPerS = 200f, alongEdgeMmPerS = 0f))

        assertThat(viewModel.state.value.playing).isEqualTo(PlayingVideoUi("received-1", isPaused = false))
        assertThat(engine.releaseCount).isEqualTo(0)
    }

    @Test
    fun `a video whose file couldn't be received stops`() = runTest {
        createViewModel(onReceive = { Result.Failure(DataError.Network.DISCONNECTED) })
        loaded()
        receiveOffer()

        peer.transport.deliver(SlideMessage.DragCommit("9", outwardMmPerS = 200f, alongEdgeMmPerS = 0f))

        assertThat(viewModel.state.value.playing).isNull()
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
