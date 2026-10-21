package com.kotlistine.transferingfilebyair.feature.pairing.presentation

sealed interface PairingAction {
    /**
     * The answer to the nearby-devices permission request (or that it was already granted), and whether the phone's
     * Location setting is on, which finding nearby phones also needs.
     */
    data class OnPermissionsResult(val granted: Boolean, val locationOn: Boolean = true) : PairingAction

    /** Handled by PairingRoot, which owns the platform permission request. */
    data object OnAllowClick : PairingAction

    /** Connect was held all the way. */
    data object OnHoldComplete : PairingAction
    data object OnRetryClick : PairingAction

    /** Turn down the other phone's request to pair. */
    data object OnDeclineClick : PairingAction

    /** Stop waiting for the other phone to accept. */
    data object OnCancelClick : PairingAction
    /** Unpair: both phones forget each other and stop linking up by themselves. */
    data object OnDisconnectClick : PairingAction

    /** Handled by PairingRoot: line the screens up (again). */
    data object OnLineUpClick : PairingAction

    /** Handled by PairingRoot: leave without pairing. */
    data object OnCloseClick : PairingAction
}
