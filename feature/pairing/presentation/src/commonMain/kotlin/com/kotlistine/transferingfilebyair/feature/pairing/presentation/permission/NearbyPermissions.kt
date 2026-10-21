package com.kotlistine.transferingfilebyair.feature.pairing.presentation.permission

import androidx.compose.runtime.Composable

/** The platform permissions needed to find and talk to nearby devices. */
interface NearbyPermissions {
    fun isGranted(): Boolean

    /** Asks for whatever is missing; the answer goes to the callback given when this was remembered. */
    fun request()

    /** For when the user declined for good: the app's system settings page. */
    fun openSettings()

    /** Whether the phone's Location setting is on: finding nearby phones needs it, besides the permissions. */
    fun isLocationOn(): Boolean

    fun openLocationSettings()
}

@Composable
expect fun rememberNearbyPermissions(onResult: (granted: Boolean) -> Unit): NearbyPermissions
