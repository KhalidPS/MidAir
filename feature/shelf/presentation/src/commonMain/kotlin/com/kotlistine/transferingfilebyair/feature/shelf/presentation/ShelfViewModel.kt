package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.domain.util.onFailure
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
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
                _state.update { state ->
                    state.copy(items = items.map { it.toUi(now, timeZone) }, isLoading = false)
                }
            }
            .launchIn(viewModelScope)
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

    private fun clearShelf() {
        viewModelScope.launch {
            repository.clear().onFailure { eventChannel.send(ShelfEvent.ShowError(it)) }
        }
    }
}
