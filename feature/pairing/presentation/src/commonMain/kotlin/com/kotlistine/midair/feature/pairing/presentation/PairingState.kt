package com.kotlistine.midair.feature.pairing.presentation

import com.kotlistine.midair.core.domain.util.DataError

enum class PairingPhase {
    /** Waiting for the user to allow finding nearby devices. */
    NEEDS_PERMISSION,

    /** Visible and looking; nobody found yet. */
    SEARCHING,

    /** The other phone is ready: hold Connect. */
    FOUND,

    /** The other phone asked to pair: check the code, then hold Connect to accept (or decline). */
    INCOMING,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class PairingState(
    val phase: PairingPhase = PairingPhase.NEEDS_PERMISSION,
    /** The other phone's name, once found ("Khalid's Pixel"). */
    val peerName: String? = null,
    /** Shown on both phones while pairing, so people can check it is the phone in front of them. */
    val code: String? = null,
    val error: DataError.Network? = null,
    /** This device can pair by scanning another one's QR code (a phone, with a laptop to pair with). */
    val canScan: Boolean = false,
    /** The camera is open, looking for a laptop's code. */
    val isScanning: Boolean = false,
    /** The linked device lines up by a swipe across both screens (a phone; a laptop places the phone itself). */
    val canLineUp: Boolean = true,
)
