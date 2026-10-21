package com.kotlistine.transferingfilebyair.feature.pairing.presentation.lineup

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

internal actual fun Modifier.keepEdgeSwipes(): Modifier = systemGestureExclusion()
