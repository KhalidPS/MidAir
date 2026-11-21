package com.kotlistine.midair.feature.pairing.presentation.scan

import androidx.compose.runtime.Composable

/**
 * The camera, full screen, looking for a QR code: [onScanned] hears the first one it reads (once), [onCancel] that the
 * user closed it or wouldn't let the camera be used. Only shown where pairing by code is possible (a phone).
 */
@Composable
expect fun QrScanner(onScanned: (String) -> Unit, onCancel: () -> Unit)
