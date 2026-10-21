package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ArrivingItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi

data class ShelfState(
    val items: List<ShelfItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val isClearConfirmationVisible: Boolean = false,
    /** A device is linked: lifted cards are mirrored to it, and cards can arrive from it. */
    val isPaired: Boolean = false,
    /** Cards from the paired device whose files are still coming in, newest first; shown above the shelf's items. */
    val arriving: List<ArrivingItemUi> = emptyList(),
    /** Received items by the id of the arriving card they took over from, so the card turns into the item in place. */
    val arrivedFrom: Map<String, String> = emptyMap(),
)
