package com.kotlistine.midair.feature.pairing.presentation.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * What Nearby Connections needs at runtime on this Android version (the rest are install-time permissions).
 * Its discovery checks for precise location on every version (seen on Android 13), even where Bluetooth scanning
 * itself no longer needs it; Android only grants precise location when approximate is asked for alongside.
 */
private val requiredPermissions: Array<String> = buildList {
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

/**
 * Asked for together, but not required: on Android 13+ the notice that keeps a transfer going in the background is
 * only visible with notification permission. Transfers work without it.
 */
private val requestedPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        requiredPermissions + Manifest.permission.POST_NOTIFICATIONS
    } else {
        requiredPermissions
    }

@Composable
actual fun rememberNearbyPermissions(onResult: (granted: Boolean) -> Unit): NearbyPermissions {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        currentOnResult(requiredPermissions.all { results[it] == true || isGranted(context, it) })
    }
    return remember(context, launcher) {
        object : NearbyPermissions {
            override fun isGranted(): Boolean = requiredPermissions.all { isGranted(context, it) }

            override fun request() = launcher.launch(requestedPermissions)

            override fun isLocationOn(): Boolean {
                val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
                return LocationManagerCompat.isLocationEnabled(manager)
            }

            override fun openLocationSettings() {
                context.startActivity(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            override fun openSettings() {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}

private fun isGranted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
