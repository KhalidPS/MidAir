package com.kotlistine.transferingfilebyair.feature.shelf.presentation.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop apps need no permission to show progress. */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission = remember {
    object : NotificationPermission {
        override fun requestIfNeeded() = Unit
    }
}
