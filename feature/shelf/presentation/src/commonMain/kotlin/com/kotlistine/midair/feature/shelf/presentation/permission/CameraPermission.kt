package com.kotlistine.midair.feature.shelf.presentation.permission

import androidx.compose.runtime.Composable

/** The camera permission, for air gestures. */
interface CameraPermission {
    fun isGranted(): Boolean

    /** Asks the user; the answer goes to the callback given to [rememberCameraPermission]. */
    fun request()

    /** Opens the system's settings where the camera can be allowed again. */
    fun openSettings()
}

@Composable
expect fun rememberCameraPermission(onResult: (granted: Boolean) -> Unit): CameraPermission
