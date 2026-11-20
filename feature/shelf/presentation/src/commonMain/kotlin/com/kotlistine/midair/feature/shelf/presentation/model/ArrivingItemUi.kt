package com.kotlistine.midair.feature.shelf.presentation.model

import androidx.compose.runtime.Immutable

/** A card from the paired device, already in its shelf slot while its file comes in. */
@Immutable
data class ArrivingItemUi(
    /** The item's id on the peer's shelf. */
    val id: String,
    val name: String,
    /** Width / height of the card's preview area, as the peer drew it. Its progress is on the transfers board. */
    val aspectRatio: Float,
)
