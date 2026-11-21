package com.kotlistine.midair.feature.shelf.presentation

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.media.VideoEngineEvent
import com.kotlistine.midair.core.media.VideoSource
import com.kotlistine.midair.core.transfer.domain.ScreenDensity
import com.kotlistine.midair.feature.shelf.presentation.FakeShelfRepository.Companion.item
import com.kotlistine.midair.feature.shelf.presentation.model.PlayingVideoUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelVideoTest {

    private lateinit var repository: FakeShelfRepository
    private lateinit var engine: FakeVideoEngine
    private lateinit var viewModel: ShelfViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeShelfRepository()
        engine = FakeVideoEngine()
        viewModel = newViewModel(videoEngine = engine)
        repository.items.value = listOf(video("1"), video("2"), item("3", name = "Q4 Report.pdf"))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tapping a video plays it in its card`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))

            assertThat(expectMostRecentItem().playing).isEqualTo(PlayingVideoUi("1", isPaused = false))
        }
        assertThat(engine.loaded).isEqualTo(VideoSource.Local("content://videos/1"))
    }

    @Test
    fun `tapping the playing video pauses it, and tapping again plays on`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))

            assertThat(expectMostRecentItem().playing).isEqualTo(PlayingVideoUi("1", isPaused = true))

            viewModel.onAction(ShelfAction.OnPreviewClick("1"))

            assertThat(expectMostRecentItem().playing).isEqualTo(PlayingVideoUi("1", isPaused = false))
        }
        assertThat(engine.releaseCount).isEqualTo(0)
    }

    @Test
    fun `tapping another video plays that one instead`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))
            viewModel.onAction(ShelfAction.OnPreviewClick("2"))

            assertThat(expectMostRecentItem().playing).isEqualTo(PlayingVideoUi("2", isPaused = false))
        }
        assertThat(engine.loaded).isEqualTo(VideoSource.Local("content://videos/2"))
    }

    @Test
    fun `tapping a file that isn't a video opens it`() = runTest {
        viewModel.state.test { awaitLoaded() }

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnPreviewClick("3"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.OpenFile("content://file/3", "application/pdf"))
        }
        assertThat(engine.loaded).isNull()
    }

    @Test
    fun `without a video engine a tapped video opens in another app`() = runTest {
        val viewModel = newViewModel(videoEngine = null)
        viewModel.state.test { awaitLoaded() }

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.OpenFile("content://videos/1", "video/mp4"))
        }
    }

    @Test
    fun `the menu's open still opens a video in another app`() = runTest {
        viewModel.state.test { awaitLoaded() }

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnOpenClick("1"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.OpenFile("content://videos/1", "video/mp4"))
        }
        assertThat(engine.loaded).isNull()
    }

    @Test
    fun `the card shows the video paused when the engine pauses by itself`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))
            engine.playWhenReady.value = false // audio focus lost

            assertThat(expectMostRecentItem().playing).isEqualTo(PlayingVideoUi("1", isPaused = true))
        }
    }

    @Test
    fun `hiding the shelf pauses the video`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))
            viewModel.onAction(ShelfAction.OnScreenHidden)

            assertThat(expectMostRecentItem().playing).isEqualTo(PlayingVideoUi("1", isPaused = true))
        }
    }

    @Test
    fun `a video that ends goes back to its still picture and frees the decoder`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))
            engine.playbackEvents.send(VideoEngineEvent.Ended)

            assertThat(expectMostRecentItem().playing).isNull()
        }
        assertThat(engine.releaseCount).isEqualTo(1)
    }

    @Test
    fun `a video that can't be played stops and the reason is shown`() = runTest {
        viewModel.state.test { awaitLoaded() }
        viewModel.onAction(ShelfAction.OnPreviewClick("1"))

        viewModel.events.test {
            engine.playbackEvents.send(VideoEngineEvent.Failed(DataError.Local.UNSUPPORTED_FORMAT))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Local.UNSUPPORTED_FORMAT))
        }
        assertThat(viewModel.state.value.playing).isNull()
        assertThat(engine.releaseCount).isEqualTo(1)
    }

    @Test
    fun `sending the playing video stops it`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))
            viewModel.onAction(ShelfAction.OnItemSent("1"))

            assertThat(expectMostRecentItem().playing).isNull()
        }
        assertThat(engine.releaseCount).isEqualTo(1)
    }

    @Test
    fun `removing another item keeps the video playing`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))
            viewModel.onAction(ShelfAction.OnRemoveClick("2"))

            assertThat(expectMostRecentItem().playing).isEqualTo(PlayingVideoUi("1", isPaused = false))
        }
        assertThat(engine.releaseCount).isEqualTo(0)
    }

    @Test
    fun `clearing the shelf stops the video`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnPreviewClick("1"))
            viewModel.onAction(ShelfAction.OnClearConfirm)

            assertThat(expectMostRecentItem().playing).isNull()
        }
        assertThat(engine.releaseCount).isEqualTo(1)
    }

    @Test
    fun `the decoder is freed when the shelf's ViewModel goes away`() = runTest {
        val store = ViewModelStore()
        store.put("shelf", viewModel)

        store.clear()

        assertThat(engine.releaseCount).isEqualTo(1)
    }

    private fun newViewModel(videoEngine: FakeVideoEngine?) = ShelfViewModel(
        repository = repository,
        density = ScreenDensity(xdpi = 254f, ydpi = 254f),
        videoEngine = videoEngine,
        timeZone = TimeZone.UTC,
    )

    private fun video(id: String) = item(id, uri = "content://videos/$id", name = "clip-$id.mp4", mimeType = "video/mp4")

    private suspend fun ReceiveTurbine<ShelfState>.awaitLoaded(): ShelfState {
        var state = awaitItem()
        while (state.isLoading) state = awaitItem()
        return state
    }
}
