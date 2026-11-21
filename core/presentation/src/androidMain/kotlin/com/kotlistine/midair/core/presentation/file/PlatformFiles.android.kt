package com.kotlistine.midair.core.presentation.file

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberFilePickerLauncher(onFilesPicked: (List<String>) -> Unit): FilePickerLauncher {
    val currentOnFilesPicked by rememberUpdatedState(onFilesPicked)
    // The document picker guarantees a persistable grant on every API level (it also lists photos), so staged
    // files survive restarts. The photo picker's grants vary between the system and the backported picker.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) currentOnFilesPicked(uris.map(Uri::toString))
    }
    return remember(launcher) { FilePickerLauncher { launcher.launch(arrayOf("*/*")) } }
}

@Composable
actual fun rememberFileOpener(): FileOpener {
    val context = LocalContext.current
    return remember(context) {
        FileOpener { uri, mimeType ->
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(uri), mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(intent)
                OpenFileResult.OPENED
            } catch (_: ActivityNotFoundException) {
                OpenFileResult.NO_APP
            } catch (_: SecurityException) {
                // Granting read access to the viewer fails when our own persisted grant is gone.
                OpenFileResult.NOT_ACCESSIBLE
            }
        }
    }
}
