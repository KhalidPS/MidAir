package com.kotlistine.midair.core.designsystem.effects

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush

/** The edge glow's colours as light flowing along the edge, at a given turn ([phase], 0..1, wrapping). */
internal fun interface FlowingGlowBrush {
    fun at(size: Size, phase: Float): Brush
}

/** A shader for the flowing glow where the platform has one (Android 13+: AGSL); null elsewhere. */
internal expect fun flowingGlowBrush(): FlowingGlowBrush?
