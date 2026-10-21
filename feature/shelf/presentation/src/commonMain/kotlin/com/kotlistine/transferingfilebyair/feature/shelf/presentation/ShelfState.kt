package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem

data class ShelfState(
    val items: List<ShelfItem> = emptyList(),
)
