package com.kotlistine.midair.feature.shelf.presentation.model

import androidx.compose.runtime.Immutable

/** Air gestures on the shelf: grab a card with a fist here, open the hand in front of the paired phone to drop it there. */
@Immutable
data class AirGestureUi(
    /** This device can watch for a hand (it has a front camera tracker). */
    val isAvailable: Boolean = false,
    /** The user turned air gestures on: a fist made over the shelf grabs a card. */
    val isOn: Boolean = false,
    /** The camera is watching for a hand right now. */
    val isWatching: Boolean = false,
    /** The card this phone's user holds in the air, until a hand opens in front of the other phone. */
    val heldId: String? = null,
    /** The name of the card in [heldId], for the hint that offers putting it back. */
    val heldName: String? = null,
    /** A card the other phone's user holds in the air: opening a hand here (or a tap) catches it. */
    val catchable: CatchableCardUi? = null,
)

@Immutable
data class CatchableCardUi(
    /** The item's id on the peer's shelf. */
    val id: String,
    val name: String,
)

/** Where the hand in front of the camera is: 0..1 across and down the screen, as seen in a mirror. */
@Immutable
data class HandPointUi(val x: Float, val y: Float, val isFist: Boolean)
