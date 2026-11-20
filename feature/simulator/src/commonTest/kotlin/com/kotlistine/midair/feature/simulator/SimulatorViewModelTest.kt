package com.kotlistine.midair.feature.simulator

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.kotlistine.midair.core.domain.util.DataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorViewModelTest {

    private val realShelf = FakeShelf()
    private val link = FakeLink()
    private lateinit var viewModel: SimulatorViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        realShelf.items.value = listOf(file("a"), file("b"))
        viewModel = SimulatorViewModel(shelf = realShelf, linkFactory = { link })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the left twin starts with a copy of the real shelf and the right one empty`() = runTest {
        assertThat(viewModel.state.value.isReady).isTrue()
        assertThat(viewModel.left.shelf.observeItems().first().map { it.uri })
            .containsExactly("content://file/a", "content://file/b")
        assertThat(viewModel.right.shelf.observeItems().first()).isEmpty()
    }

    @Test
    fun `changing the latency slows the link`() = runTest {
        viewModel.onAction(SimulatorAction.OnLatencyChange(60))

        assertThat(viewModel.state.value.latencyMs).isEqualTo(60)
        assertThat(link.latency).isEqualTo(60.milliseconds)
    }

    @Test
    fun `changing the gap moves both twins' screens apart`() = runTest {
        viewModel.onAction(SimulatorAction.OnGapChange(20f))

        assertThat(viewModel.state.value.gapMm).isEqualTo(20f)
        assertThat(viewModel.left.peer.link.gapMm).isEqualTo(20f)
        assertThat(viewModel.right.peer.link.gapMm).isEqualTo(20f)
    }

    @Test
    fun `resetting restocks the twins and says so`() = runTest {
        viewModel.right.shelf.addFile("content://extra")

        viewModel.events.test {
            viewModel.onAction(SimulatorAction.OnResetClick)

            assertThat(awaitItem()).isEqualTo(SimulatorEvent.TwinsReset)
        }
        assertThat(viewModel.right.shelf.observeItems().first()).isEmpty()
        assertThat(viewModel.left.shelf.observeItems().first().map { it.uri })
            .containsExactly("content://extra", "content://file/a", "content://file/b")
    }

    @Test
    fun `a file picked on a twin is staged on the real shelf too`() = runTest {
        viewModel.left.shelf.addFile("content://picked")

        assertThat(realShelf.items.value.first().uri).isEqualTo("content://picked")
        assertThat(viewModel.left.shelf.observeItems().first().first().uri).isEqualTo("content://picked")
    }

    @Test
    fun `a file the real shelf can't read isn't added to the twin`() = runTest {
        realShelf.addFailure = DataError.Local.PERMISSION_DENIED

        viewModel.left.shelf.addFile("content://locked")

        assertThat(viewModel.left.shelf.observeItems().first().map { it.uri })
            .containsExactly("content://file/a", "content://file/b")
    }
}
