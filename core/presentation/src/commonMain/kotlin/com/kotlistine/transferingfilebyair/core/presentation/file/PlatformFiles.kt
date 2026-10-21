package com.kotlistine.transferingfilebyair.core.presentation.file

import androidx.compose.runtime.Composable

fun interface FilePickerLauncher {
    fun launch()
}

/**
 * Opens the platform's multi-file picker. [onFilesPicked] receives platform URIs that stay readable
 * across restarts once the data layer retains access; it is not called when the user cancels.
 */
@Composable
expect fun rememberFilePickerLauncher(onFilesPicked: (List<String>) -> Unit): FilePickerLauncher

enum class OpenFileResult {
    OPENED,

    /** No installed app handles this file type. */
    NO_APP,

    /** The file was deleted or access to it was revoked since it was added. */
    NOT_ACCESSIBLE,
}

fun interface FileOpener {
    fun open(uri: String, mimeType: String): OpenFileResult
}

@Composable
expect fun rememberFileOpener(): FileOpener
