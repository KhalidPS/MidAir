package com.kotlistine.transferingfilebyair.feature.pairing.presentation

sealed interface PairingEvent {
    /** The link just came up: light up, then move on. Both phones get this at the same moment. */
    data class Paired(val peerName: String) : PairingEvent
}
