package com.kotlistine.transferingfilebyair.feature.shelf.presentation.model

/**
 * A card the paired device is sliding over, sized for this screen (same millimetres as on the sender).
 * Not a data class: [thumbnail] would be compared by identity.
 */
class IncomingCardUi(
    val id: String,
    val name: String,
    val widthPx: Float,
    val heightPx: Float,
    /** The card as the sender drew it; null if it couldn't be captured. */
    val thumbnail: ByteArray?,
)
