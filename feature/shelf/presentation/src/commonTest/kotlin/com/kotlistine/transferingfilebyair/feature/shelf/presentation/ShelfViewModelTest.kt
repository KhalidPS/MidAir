package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.FakeShelfRepository.Companion.item
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
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModelTest {

    private lateinit var repository: FakeShelfRepository
    private lateinit var viewModel: ShelfViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeShelfRepository()
        viewModel = ShelfViewModel(
            repository = repository,
            density = ScreenDensity(xdpi = 254f, ydpi = 254f),
            clock = FixedClock,
            timeZone = TimeZone.UTC,
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `shows the shelf items once loaded`() = runTest {
        repository.items.value = listOf(item("2", name = "Q4 Report.pdf"), item("1", name = "Meeting notes.md"))

        viewModel.state.test {
            val loaded = awaitLoaded()
            assertThat(loaded.items.map { it.name }).containsExactly("Q4 Report.pdf", "Meeting notes.md")
            assertThat(loaded.items.first().details).isEqualTo("16 KB · 00:00")
        }
    }

    @Test
    fun `an empty shelf finishes loading with no items`() = runTest {
        viewModel.state.test {
            assertThat(awaitLoaded().items).isEmpty()
        }
    }

    @Test
    fun `picked files are added to the shelf`() = runTest {
        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnFilesPicked(listOf("content://a", "content://b")))

            assertThat(expectMostRecentItem().items.map { it.uri }).containsExactly("content://b", "content://a")
        }
    }

    @Test
    fun `failed additions are reported once with how many failed`() = runTest {
        repository.failingUris["content://a"] = DataError.Local.PERMISSION_DENIED
        repository.failingUris["content://c"] = DataError.Local.FILE_NOT_FOUND

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnFilesPicked(listOf("content://a", "content://b", "content://c")))

            assertThat(awaitItem()).isEqualTo(
                ShelfEvent.ShowAddFailed(failedCount = 2, error = DataError.Local.PERMISSION_DENIED),
            )
            expectNoEvents()
        }
        assertThat(repository.items.value.map { it.uri }).containsExactly("content://b")
    }

    @Test
    fun `opening an item asks the UI to open its file`() = runTest {
        repository.items.value = listOf(item("7", uri = "content://docs/7"))
        viewModel.state.test { awaitLoaded() }

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnOpenClick("7"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.OpenFile(uri = "content://docs/7", mimeType = "application/pdf"))
        }
    }

    @Test
    fun `opening an unknown item does nothing`() = runTest {
        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnOpenClick("missing"))

            expectNoEvents()
        }
    }

    @Test
    fun `removing an item takes it off the shelf`() = runTest {
        repository.items.value = listOf(item("1"), item("2"))

        viewModel.onAction(ShelfAction.OnRemoveClick("1"))

        assertThat(repository.items.value.map { it.id }).containsExactly("2")
    }

    @Test
    fun `a failed removal is shown as an error`() = runTest {
        repository.items.value = listOf(item("1"))
        repository.removeFailure = DataError.Local.UNKNOWN

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnRemoveClick("1"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Local.UNKNOWN))
        }
    }

    @Test
    fun `clearing asks for confirmation first`() = runTest {
        repository.items.value = listOf(item("1"))

        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnClearClick)

            assertThat(awaitItem().isClearConfirmationVisible).isTrue()
            assertThat(repository.items.value).isEqualTo(listOf(item("1")))
        }
    }

    @Test
    fun `confirming clears the shelf and closes the dialog`() = runTest {
        repository.items.value = listOf(item("1"), item("2"))

        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnClearClick)
            viewModel.onAction(ShelfAction.OnClearConfirm)

            val latest = expectMostRecentItem()
            assertThat(latest.isClearConfirmationVisible).isFalse()
            assertThat(latest.items).isEmpty()
        }
    }

    @Test
    fun `dismissing the confirmation keeps the shelf`() = runTest {
        repository.items.value = listOf(item("1"))

        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnClearClick)
            viewModel.onAction(ShelfAction.OnClearDismiss)

            assertThat(expectMostRecentItem().isClearConfirmationVisible).isFalse()
        }
        assertThat(repository.items.value).isEqualTo(listOf(item("1")))
    }

    @Test
    fun `a failed clear is shown as an error`() = runTest {
        repository.clearFailure = DataError.Local.DISK_FULL

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnClearConfirm)

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Local.DISK_FULL))
        }
    }

    @Test
    fun `a sent item leaves the shelf at once but stays stored while undo is offered`() = runTest {
        repository.items.value = listOf(item("1"), item("2"))

        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnItemSent("1"))

            assertThat(expectMostRecentItem().items.map { it.id }).containsExactly("2")
        }
        assertThat(repository.items.value.map { it.id }).containsExactly("1", "2")
    }

    @Test
    fun `sending offers to undo it`() = runTest {
        repository.items.value = listOf(item("1", name = "Q4 Report.pdf"))
        viewModel.state.test { awaitLoaded() }

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnItemSent("1"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowSent(id = "1", name = "Q4 Report.pdf"))
        }
    }

    @Test
    fun `a sent item is removed for good once the undo offer closes`() = runTest {
        repository.items.value = listOf(item("1"), item("2"))

        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnItemSent("1"))
            viewModel.onAction(ShelfAction.OnUndoOfferClosed("1"))

            assertThat(repository.items.value.map { it.id }).containsExactly("2")
            assertThat(expectMostRecentItem().items.map { it.id }).containsExactly("2")
        }
    }

    @Test
    fun `undoing a send brings the item back and keeps it on the shelf`() = runTest {
        repository.items.value = listOf(item("1"), item("2"))

        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnItemSent("1"))
            viewModel.onAction(ShelfAction.OnUndoSend("1"))

            assertThat(expectMostRecentItem().items.map { it.id }).containsExactly("1", "2")
        }
        assertThat(repository.items.value.map { it.id }).containsExactly("1", "2")
    }

    @Test
    fun `undo after the offer closed does nothing`() = runTest {
        repository.items.value = listOf(item("1"), item("2"))

        viewModel.state.test {
            awaitLoaded()
            viewModel.onAction(ShelfAction.OnItemSent("1"))
            viewModel.onAction(ShelfAction.OnUndoOfferClosed("1"))
            viewModel.onAction(ShelfAction.OnUndoSend("1"))

            assertThat(expectMostRecentItem().items.map { it.id }).containsExactly("2")
        }
    }

    @Test
    fun `a sent item that fails to be removed comes back with the error`() = runTest {
        repository.items.value = listOf(item("1"))
        repository.removeFailure = DataError.Local.UNKNOWN
        viewModel.state.test { awaitLoaded() }

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnItemSent("1"))
            assertThat(awaitItem()).isInstanceOf(ShelfEvent.ShowSent::class)
            viewModel.onAction(ShelfAction.OnUndoOfferClosed("1"))

            assertThat(awaitItem()).isEqualTo(ShelfEvent.ShowError(DataError.Local.UNKNOWN))
        }
        viewModel.state.test {
            assertThat(expectMostRecentItem().items.map { it.id }).containsExactly("1")
        }
    }

    @Test
    fun `clearing the shelf drops sends that are still undoable`() = runTest {
        repository.items.value = listOf(item("1"), item("2"))
        viewModel.state.test { awaitLoaded() }

        viewModel.events.test {
            viewModel.onAction(ShelfAction.OnItemSent("1"))
            assertThat(awaitItem()).isInstanceOf(ShelfEvent.ShowSent::class)
            viewModel.onAction(ShelfAction.OnClearConfirm)
            viewModel.onAction(ShelfAction.OnUndoOfferClosed("1"))

            // The removal never runs against the emptied shelf, so there's no "not found" error.
            expectNoEvents()
        }
        assertThat(repository.items.value).isEmpty()
    }

    private suspend fun ReceiveTurbine<ShelfState>.awaitLoaded(): ShelfState {
        var state = awaitItem()
        while (state.isLoading) state = awaitItem()
        return state
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }
}
