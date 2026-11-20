package com.kotlistine.midair.core.designsystem.effects

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kotlistine.midair.core.designsystem.theme.AirColors

/** Whether the platform can blur a layer (Android: RenderEffect, API 31+). */
internal expect val isBlurSupported: Boolean

/**
 * Pushes content back so something in front of it becomes the only sharp thing on screen: blur, a dim and a
 * slight scale-down, all scaled by [progress] (0 = untouched, 1 = fully receded). Without blur support the dim
 * gets heavier instead. [progress] is read in the draw phase, so animating it never recomposes.
 */
fun Modifier.recede(
    progress: () -> Float,
    blurRadius: Dp = 20.dp,
    scrim: Color = AirColors.Background,
): Modifier = this
    .graphicsLayer {
        val p = progress()
        val scale = 1f - 0.04f * p
        scaleX = scale
        scaleY = scale
        val radius = blurRadius.toPx() * p
        // Decal lets the edges fade into the background instead of smearing the outermost pixels.
        renderEffect = if (isBlurSupported && radius >= 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
    }
    .drawWithContent {
        drawContent()
        val p = progress()
        if (p > 0f) drawRect(scrim.copy(alpha = p * if (isBlurSupported) 0.3f else 0.62f))
    }
