package com.kotlistine.transferingfilebyair.feature.shelf.presentation.model

import androidx.compose.runtime.Immutable

/** A card from the paired device, already in its shelf slot while its file comes in. */
@Immutable
data class ArrivingItemUi(
    /** The item's id on the peer's shelf. */
    val id: String,
    val name: String,
    /** Width / height of the card's preview area, as the peer drew it. */
    val aspectRatio: Float,
    /** How much of the file is here (0..1). */
    val progress: Float = 0f,
)
