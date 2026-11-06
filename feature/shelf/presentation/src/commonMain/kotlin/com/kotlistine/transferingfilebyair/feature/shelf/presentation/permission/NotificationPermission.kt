package com.kotlistine.transferingfilebyair.feature.shelf.presentation.permission

import androidx.compose.runtime.Composable

/** Showing notifications, for the progress of files moving in the background. */
interface NotificationPermission {
    /** Asks the user, unless it is granted already or isn't needed on this platform; the answer isn't needed. */
    fun requestIfNeeded()
}

@Composable
expect fun rememberNotificationPermission(): NotificationPermission
