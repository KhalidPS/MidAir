package com.kotlistine.transferingfilebyair.feature.shelf.presentation.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberCameraPermission(onResult: (granted: Boolean) -> Unit): CameraPermission {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentOnResult(granted)
    }
    return remember(context, launcher) {
        object : CameraPermission {
            override fun isGranted(): Boolean =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

            override fun request() = launcher.launch(Manifest.permission.CAMERA)

            override fun openSettings() {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }
    }
}
