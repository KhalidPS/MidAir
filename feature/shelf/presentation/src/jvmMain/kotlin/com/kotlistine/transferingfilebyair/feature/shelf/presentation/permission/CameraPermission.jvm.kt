package com.kotlistine.transferingfilebyair.feature.shelf.presentation.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * A desktop app has no camera permission of its own to ask for: the system's privacy settings decide, and a webcam
 * they block fails to open in the hand tracker, which shows the camera as unavailable.
 */
@Composable
actual fun rememberCameraPermission(onResult: (granted: Boolean) -> Unit): CameraPermission = remember {
    object : CameraPermission {
        override fun isGranted() = true
        override fun request() = onResult(true)
    }
}
