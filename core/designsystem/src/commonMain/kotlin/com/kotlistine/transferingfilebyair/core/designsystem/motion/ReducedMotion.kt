package com.kotlistine.transferingfilebyair.core.designsystem.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the person asked for less motion (Android: "Remove animations"). Ordinary animations already finish at
 * once then; what this stops is motion that never ends on its own, like a glow that keeps turning. Anything the
 * finger drives still follows it. Provided by AirTheme.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal expect fun systemReducedMotion(): Boolean
