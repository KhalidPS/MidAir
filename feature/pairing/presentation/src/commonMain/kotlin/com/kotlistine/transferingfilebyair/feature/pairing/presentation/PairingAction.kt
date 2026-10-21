package com.kotlistine.transferingfilebyair.feature.pairing.presentation

sealed interface PairingAction {
    /** The answer to the nearby-devices permission request (or that it was already granted). */
    data class OnPermissionsResult(val granted: Boolean) : PairingAction

    /** Handled by PairingRoot, which owns the platform permission request. */
    data object OnAllowClick : PairingAction

    /** Connect was held all the way. */
    data object OnHoldComplete : PairingAction
    data object OnRetryClick : PairingAction

    /** Turn down the other phone's request to pair. */
    data object OnDeclineClick : PairingAction

    /** Stop waiting for the other phone to accept. */
    data object OnCancelClick : PairingAction
    data object OnDisconnectClick : PairingAction

    /** Handled by PairingRoot: leave without pairing. */
    data object OnCloseClick : PairingAction
}
