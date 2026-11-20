package com.kotlistine.midair.core.presentation.file

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

/** The system's file dialog. Files are referred to by their `file:///` URI, which stays valid across restarts. */
@Composable
actual fun rememberFilePickerLauncher(onFilesPicked: (List<String>) -> Unit): FilePickerLauncher {
    val callback = rememberUpdatedState(onFilesPicked)
    return remember {
        FilePickerLauncher {
            val dialog = FileDialog(null as Frame?, "Add files", FileDialog.LOAD).apply {
                isMultipleMode = true
                isVisible = true
            }
            val picked = dialog.files.orEmpty().map { it.toPath().toUri().toString() }
            if (picked.isNotEmpty()) callback.value(picked)
        }
    }
}

@Composable
actual fun rememberFileOpener(): FileOpener = remember {
    FileOpener { uri, _ ->
        val file = runCatching { File(URI(uri)) }.getOrNull()
        when {
            file == null || !file.exists() -> OpenFileResult.NOT_ACCESSIBLE
            !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN) ->
                OpenFileResult.NO_APP
            else -> runCatching { Desktop.getDesktop().open(file) }
                .fold(onSuccess = { OpenFileResult.OPENED }, onFailure = { OpenFileResult.NO_APP })
        }
    }
}
