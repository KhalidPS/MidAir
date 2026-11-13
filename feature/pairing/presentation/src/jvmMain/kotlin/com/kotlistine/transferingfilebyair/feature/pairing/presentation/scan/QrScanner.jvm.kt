package com.kotlistine.transferingfilebyair.feature.pairing.presentation.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** The desktop shows the code, it doesn't scan one (it binds no InviteLink, so this never opens). */
@Composable
actual fun QrScanner(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    LaunchedEffect(Unit) { onCancel() }
}
