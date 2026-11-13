package com.kotlistine.transferingfilebyair.core.designsystem.motion

import androidx.compose.runtime.Composable

/** The desktop has no system-wide "remove animations" setting that the JVM can read. */
@Composable
internal actual fun systemReducedMotion(): Boolean = false
