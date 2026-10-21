package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi

data class ShelfState(
    val items: List<ShelfItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val isClearConfirmationVisible: Boolean = false,
)
