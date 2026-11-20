package com.kotlistine.transferingfilebyair.feature.shelf.presentation.permission

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import java.util.prefs.Preferences

private const val CameraAllowedKey = "camera_allowed_for_air_gestures"

/**
 * A desktop app can't trigger the system's camera prompt itself, so the user's consent is asked for here, once, the
 * first time air gestures are turned on, and kept like a granted Android permission. The system's privacy settings
 * still decide on top of it: a webcam they block fails to open in the hand tracker, which shows it as unavailable.
 */
@Composable
actual fun rememberCameraPermission(onResult: (granted: Boolean) -> Unit): CameraPermission {
    val currentOnResult by rememberUpdatedState(onResult)
    var isAsking by remember { mutableStateOf(false) }

    if (isAsking) {
        fun answer(granted: Boolean) {
            isAsking = false
            if (granted) CameraConsent.allow()
            currentOnResult(granted)
        }
        AlertDialog(
            onDismissRequest = { answer(false) },
            title = { Text("Use the camera for air gestures?") },
            text = {
                Text(
                    "Your webcam watches for your hand while air gestures are on. The picture is read on this " +
                        "computer only and is never saved or sent anywhere.",
                )
            },
            confirmButton = { TextButton(onClick = { answer(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { answer(false) }) { Text("Not now") } },
            containerColor = AirColors.SurfaceRaised,
        )
    }

    return remember {
        object : CameraPermission {
            override fun isGranted() = CameraConsent.isAllowed()
            override fun request() {
                if (CameraConsent.isAllowed()) currentOnResult(true) else isAsking = true
            }

            override fun openSettings() = openCameraPrivacySettings()
        }
    }
}

/** The system's camera privacy page: Windows Settings, or macOS System Settings; Linux has none to open. */
private fun openCameraPrivacySettings() {
    val os = System.getProperty("os.name").lowercase()
    val command = when {
        "win" in os -> listOf("explorer.exe", "ms-settings:privacy-webcam")
        "mac" in os -> listOf("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera")
        else -> return
    }
    runCatching { ProcessBuilder(command).start() }
}

/** The user's answer, kept across restarts in this user's preferences. */
private object CameraConsent {
    private val preferences: Preferences? = runCatching {
        Preferences.userRoot().node("com/kotlistine/transferingfilebyair")
    }.getOrNull()

    fun isAllowed(): Boolean = runCatching { preferences?.getBoolean(CameraAllowedKey, false) }.getOrNull() ?: false

    fun allow() {
        runCatching {
            preferences?.putBoolean(CameraAllowedKey, true)
            preferences?.flush()
        }
    }
}
