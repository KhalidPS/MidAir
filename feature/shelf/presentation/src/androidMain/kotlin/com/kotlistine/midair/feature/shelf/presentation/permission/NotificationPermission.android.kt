package com.kotlistine.midair.feature.shelf.presentation.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Android 13+ asks for it; before that, notifications need no permission. */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    // Transfers run either way; without it the progress just isn't shown outside the app.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context, launcher) {
        object : NotificationPermission {
            override fun requestIfNeeded() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
                val permission = Manifest.permission.POST_NOTIFICATIONS
                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return
                launcher.launch(permission)
            }
        }
    }
}
