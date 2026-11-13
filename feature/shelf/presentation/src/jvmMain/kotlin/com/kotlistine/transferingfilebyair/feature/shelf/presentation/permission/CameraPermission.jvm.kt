package com.kotlistine.transferingfilebyair.feature.shelf.presentation.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The desktop binds no hand tracker, so the camera is never used and never granted. */
@Composable
actual fun rememberCameraPermission(onResult: (granted: Boolean) -> Unit): CameraPermission = remember {
    object : CameraPermission {
        override fun isGranted() = false
        override fun request() = onResult(false)
    }
}
