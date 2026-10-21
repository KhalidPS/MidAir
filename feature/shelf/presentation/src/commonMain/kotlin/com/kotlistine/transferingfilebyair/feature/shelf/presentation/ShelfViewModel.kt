package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.domain.util.onFailure
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.toUi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

class ShelfViewModel(
    private val repository: ShelfRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private var isObservingItems = false

    /** Everything in the repository, including items that were just sent and are waiting out the undo window. */
    private var shelfItems: List<ShelfItemUi> = emptyList()

    /** Sent items, hidden from the shelf until they are removed for good (or brought back). */
    private val sentIds = mutableSetOf<String>()
    private var isClearing = false

    private val _state = MutableStateFlow(ShelfState())
    val state: StateFlow<ShelfState> = _state
        .onStart {
            if (!isObservingItems) {
                isObservingItems = true
                observeItems()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ShelfState())

    private val eventChannel = Channel<ShelfEvent>()
    val events = eventChannel.receiveAsFlow()

    fun onAction(action: ShelfAction) {
        when (action) {
            ShelfAction.OnAddClick -> Unit
            is ShelfAction.OnFilesPicked -> addFiles(action.uris)
            is ShelfAction.OnOpenClick -> openItem(action.id)
            is ShelfAction.OnRemoveClick -> removeItem(action.id)
            is ShelfAction.OnItemSent -> sendItem(action.id)
            is ShelfAction.OnUndoSend -> undoSend(action.id)
            is ShelfAction.OnUndoOfferClosed -> finishSend(action.id)
            ShelfAction.OnClearClick -> _state.update { it.copy(isClearConfirmationVisible = true) }
            ShelfAction.OnClearDismiss -> _state.update { it.copy(isClearConfirmationVisible = false) }
            ShelfAction.OnClearConfirm -> {
                _state.update { it.copy(isClearConfirmationVisible = false) }
                clearShelf()
            }
        }
    }

    private fun observeItems() {
        repository.observeItems()
            .onEach { items ->
                val now = clock.now()
                shelfItems = items.map { it.toUi(now, timeZone) }
                publishItems()
            }
            .launchIn(viewModelScope)
    }

    private fun publishItems() {
        _state.update { state ->
            state.copy(items = shelfItems.filterNot { it.id in sentIds }, isLoading = false)
        }
    }

    private fun addFiles(uris: List<String>) {
        viewModelScope.launch {
            val failures = uris.mapNotNull { uri -> (repository.addFile(uri) as? Result.Failure)?.error }
            if (failures.isNotEmpty()) {
                eventChannel.send(ShelfEvent.ShowAddFailed(failedCount = failures.size, error = failures.first()))
            }
        }
    }

    private fun openItem(id: String) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            eventChannel.send(ShelfEvent.OpenFile(uri = item.uri, mimeType = item.mimeType))
        }
    }

    private fun removeItem(id: String) {
        viewModelScope.launch {
            repository.removeItem(id).onFailure { eventChannel.send(ShelfEvent.ShowError(it)) }
        }
    }

    /**
     * The item leaves the shelf at once (the grid closes the gap) and the UI offers to undo it. It is only removed
     * from the repository once that offer closes ([ShelfAction.OnUndoOfferClosed]), however long the offer stays
     * up, so Undo always works while it is visible. Until a paired device receives it, a slip loses nothing.
     */
    private fun sendItem(id: String) {
        val item = shelfItems.firstOrNull { it.id == id } ?: return
        if (!sentIds.add(id)) return
        publishItems()
        viewModelScope.launch { eventChannel.send(ShelfEvent.ShowSent(id = id, name = item.name)) }
    }

    private fun undoSend(id: String) {
        if (sentIds.remove(id)) publishItems()
    }

    private fun finishSend(id: String) {
        // A clear in flight takes sent items with it; removing them as well would race it.
        if (id !in sentIds || isClearing) return
        viewModelScope.launch {
            val removal = repository.removeItem(id)
            sentIds.remove(id)
            removal.onFailure { error ->
                publishItems()
                eventChannel.send(ShelfEvent.ShowError(error))
            }
        }
    }

    private fun clearShelf() {
        // Sent items go with everything else; they stay hidden until the clear lands.
        isClearing = true
        viewModelScope.launch {
            val cleared = repository.clear()
            isClearing = false
            sentIds.clear()
            cleared.onFailure {
                publishItems()
                eventChannel.send(ShelfEvent.ShowError(it))
            }
        }
    }
}
