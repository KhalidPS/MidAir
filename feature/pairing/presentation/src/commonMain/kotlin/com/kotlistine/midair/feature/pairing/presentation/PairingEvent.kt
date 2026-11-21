package com.kotlistine.midair.feature.pairing.presentation

sealed interface PairingEvent {
    /** The link just came up: light up, then move on. Both phones get this at the same moment. */
    data class Paired(
        val peerName: String,
        /** Whether the screens are lined up next, by a swipe across both (phones; a laptop places the phone itself). */
        val linesUp: Boolean = true,
    ) : PairingEvent
}
