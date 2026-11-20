package com.kotlistine.midair.core.designsystem.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kotlistine.midair.core.designsystem.theme.AirColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * A multicolour light flowing around the rounded screen edge, slowly turning and breathing (the pairing screen's
 * halo). [phase] is the turn (0..1, wraps) and [intensity] the strength (0 dark, 1 resting, up to 2 charged); both
 * are read in the draw phase, so animating them never recomposes. On Android 13+ a shader makes the colours flow
 * along the edge; before that they turn as a sweep. Below API 31 the soft halo is layered strokes instead of a blur.
 */
@Composable
fun EdgeGlow(
    phase: () -> Float,
    intensity: () -> Float,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 36.dp,
) {
    val flowing = remember { flowingGlowBrush() }
    Box(modifier.fillMaxSize()) {
        // Soft halo.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (isBlurSupported) {
                        val radius = 16.dp.toPx()
                        renderEffect = BlurEffect(radius, radius, TileMode.Decal)
                    }
                }
                .drawBehind {
                    val i = intensity()
                    if (isBlurSupported) {
                        drawGlowRing(phase(), i, width = 26.dp, alpha = 0.6f, cornerRadius, flowing)
                    } else {
                        drawGlowRing(phase(), i, width = 30.dp, alpha = 0.12f, cornerRadius, flowing)
                        drawGlowRing(phase(), i, width = 16.dp, alpha = 0.22f, cornerRadius, flowing)
                    }
                },
        )
        // Crisp line on the very edge.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind { drawGlowRing(phase(), intensity(), width = 3.dp, alpha = 0.95f, cornerRadius, flowing) },
        )
    }
}

private fun DrawScope.drawGlowRing(
    phase: Float,
    intensity: Float,
    width: Dp,
    alpha: Float,
    cornerRadius: Dp,
    flowing: FlowingGlowBrush?,
) {
    if (intensity <= 0.01f) return
    val strength = intensity.coerceIn(0f, 2f)
    // Breathes three times per turn; a charged glow is also wider.
    val breath = 1f + 0.18f * sin(phase * 2f * PI.toFloat() * 3f)
    val stroke = width.toPx() * breath * (0.6f + 0.4f * strength)
    val inset = stroke / 2f
    drawRoundRect(
        brush = flowing?.at(size, phase) ?: turnedSweep(phase, center),
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(cornerRadius.toPx()),
        style = Stroke(width = stroke),
        alpha = (alpha * strength.coerceAtMost(1f)).coerceIn(0f, 1f),
    )
}

/** [AirColors.GlowSweep] around [center], turned by [phase] of a full circle (rotating the colours, not the shape). */
private fun turnedSweep(phase: Float, center: Offset): Brush {
    val colors = AirColors.GlowSweep.dropLast(1) // the last repeats the first so a sweep loops seamlessly
    val n = colors.size
    val shift = ((phase % 1f) + 1f) % 1f
    val stops = colors.mapIndexed { index, color -> ((index.toFloat() / n + shift) % 1f) to color }.sortedBy { it.first }
    // The colour at the seam (0 / 1) is between the last stop and the first one, wrapping around.
    val (lastPos, lastColor) = stops.last()
    val (firstPos, firstColor) = stops.first()
    val span = (1f - lastPos) + firstPos
    val seam = if (span > 0f) lerp(lastColor, firstColor, (1f - lastPos) / span) else firstColor
    val all: List<Pair<Float, Color>> = listOf(0f to seam) + stops + listOf(1f to seam)
    return Brush.sweepGradient(*all.toTypedArray(), center = center)
}
