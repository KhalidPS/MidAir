package com.kotlistine.midair.feature.pairing.presentation.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The desktop finds devices on the local network, which needs no runtime permission. */
@Composable
actual fun rememberNearbyPermissions(onResult: (granted: Boolean) -> Unit): NearbyPermissions = remember {
    object : NearbyPermissions {
        override fun isGranted() = true
        override fun request() = onResult(true)
        override fun openSettings() = Unit
        override fun isLocationOn() = true
        override fun openLocationSettings() = Unit
    }
}
