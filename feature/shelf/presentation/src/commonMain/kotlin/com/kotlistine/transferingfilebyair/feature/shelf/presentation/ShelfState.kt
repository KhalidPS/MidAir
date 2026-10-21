package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi

data class ShelfState(
    val items: List<ShelfItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val isClearConfirmationVisible: Boolean = false,
    /** A device is linked: lifted cards are mirrored to it, and cards can arrive from it. */
    val isPaired: Boolean = false,
    /** A received item whose card is still flying into its slot; the grid keeps that slot empty until it lands. */
    val landingItemId: String? = null,
)
