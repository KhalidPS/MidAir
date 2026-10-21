package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class ShelfViewModel : ViewModel() {

    private val _state = MutableStateFlow(ShelfState())
    val state: StateFlow<ShelfState> = _state.asStateFlow()

    private val eventChannel = Channel<ShelfEvent>()
    val events = eventChannel.receiveAsFlow()

    fun onAction(action: ShelfAction) {
    }
}
