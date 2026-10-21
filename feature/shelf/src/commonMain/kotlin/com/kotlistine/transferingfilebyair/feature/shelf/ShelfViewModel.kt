package com.kotlistine.transferingfilebyair.feature.shelf

import androidx.lifecycle.ViewModel
import com.kotlistine.transferingfilebyair.core.model.ShelfItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ShelfUiState(
    val items: List<ShelfItem> = emptyList(),
)

class ShelfViewModel : ViewModel() {
    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()
}
