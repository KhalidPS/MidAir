package com.kotlistine.transferingfilebyair.feature.simulator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.SimulatedLinkFactory
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** One virtual device: its own shelf, and its link to the other twin. */
class Twin internal constructor(val shelf: ShelfRepository, val peer: SlidePeer)

/**
 * Two virtual devices side by side, joined by an in-memory link: the left twin starts with a copy of the real shelf,
 * the right one empty. Each twin runs the real shelf screen and ViewModel, so everything but the radio is the
 * production code path.
 */
class SimulatorViewModel(
    private val shelf: ShelfRepository,
    linkFactory: SimulatedLinkFactory,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    private val _state = MutableStateFlow(SimulatorState())
    val state: StateFlow<SimulatorState> = _state.asStateFlow()

    private val eventChannel = Channel<SimulatorEvent>()
    val events = eventChannel.receiveAsFlow()

    private val link = linkFactory.create(viewModelScope)
    private val storage = SimulatedStorage()
    private val now = { clock.now().toEpochMilliseconds() }
    private val leftShelf = InMemoryShelfRepository("L", storage, now, ::importFile)
    private val rightShelf = InMemoryShelfRepository("R", storage, now, ::importFile)

    // The left twin's right edge faces the right twin, and vice versa.
    val left = Twin(leftShelf, TwinPeer(link.left, ExitEdge.RIGHT, { _state.value.gapMm }, storage, leftShelf))
    val right = Twin(rightShelf, TwinPeer(link.right, ExitEdge.LEFT, { _state.value.gapMm }, storage, rightShelf))

    init {
        viewModelScope.launch {
            stockTwins()
            _state.update { it.copy(isReady = true) }
        }
    }

    fun onAction(action: SimulatorAction) {
        when (action) {
            is SimulatorAction.OnLatencyChange -> {
                link.latency = action.latencyMs.milliseconds
                _state.update { it.copy(latencyMs = action.latencyMs) }
            }
            is SimulatorAction.OnGapChange -> _state.update { it.copy(gapMm = action.gapMm) }
            SimulatorAction.OnResetClick -> viewModelScope.launch {
                stockTwins()
                eventChannel.send(SimulatorEvent.TwinsReset)
            }
        }
    }

    private suspend fun stockTwins() {
        leftShelf.reset(shelf.observeItems().first())
        rightShelf.reset(emptyList())
    }

    /** A file picked on a twin is read (and staged) by the real shelf, then copied onto the twin. */
    private suspend fun importFile(uri: String): Result<ShelfItem, DataError.Local> {
        val added = shelf.addFile(uri)
        if (added is Result.Failure) return added
        val item = shelf.observeItems().first().firstOrNull { it.uri == uri }
        return if (item != null) Result.Success(item) else Result.Failure(DataError.Local.FILE_NOT_FOUND)
    }
}
