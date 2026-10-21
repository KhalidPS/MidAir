package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirDimens
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.FilePreview
import kotlin.math.roundToInt

private val LiftedElevation = 28.dp

/**
 * The carried card. Laid out once at the slot's exact pixel size; every frame only changes its layer transform.
 * Put it in a full-screen layer so the window edge is what clips it.
 */
@Composable
internal fun LiftedCard(state: SlideState, modifier: Modifier = Modifier) {
    val item = state.item ?: return
    val rest = state.restRect
    // The card's content (untransformed) is recorded as it draws, so the slide can send a picture of it.
    val recording = rememberGraphicsLayer()
    SideEffect { state.cardLayer = recording }
    FilePreview(
        item = item,
        modifier = modifier
            .layout { measurable, _ ->
                val width = rest.width.roundToInt()
                val height = rest.height.roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val offset = state.offset.value
                val lift = state.lift.value
                translationX = rest.left + offset.x
                translationY = rest.top + offset.y
                val scale = 1f + (LiftedScale - 1f) * lift
                scaleX = scale
                scaleY = scale
                rotationZ = state.tilt.value
                shadowElevation = LiftedElevation.toPx() * lift.coerceIn(0f, 1f)
                shape = RoundedCornerShape(AirDimens.CardCorner)
                clip = true
            }
            .drawWithContent {
                recording.record { this@drawWithContent.drawContent() }
                drawLayer(recording)
            },
    )
}

/**
 * White light swelling from an edge, with a prism-coloured rim: where a card is heading on the sender, where it is
 * arriving on the receiver. [bloom] gets the layer width and is read in the draw phase only.
 */
@Composable
internal fun EdgeBloom(bloom: (width: Float) -> Bloom?, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind { bloom(size.width)?.let { drawBloom(it) } },
    )
}

private fun DrawScope.drawBloom(bloom: Bloom) {
    val intensity = bloom.intensity
    if (intensity <= 0.005f) return
    val alpha = intensity.coerceAtMost(1f)
    val radius = size.width * (0.25f + 0.55f * intensity)
    // The source sits just beyond the edge, so the screen shows the bright near side of it.
    val centerX = if (bloom.edge == ExitEdge.RIGHT) size.width + radius * 0.2f else -radius * 0.2f
    val center = Offset(centerX, bloom.centerY)
    val core = AirColors.BloomCore
    drawCircle(
        brush = Brush.radialGradient(
            0f to core.copy(alpha = 0.9f * alpha),
            0.35f to core.copy(alpha = 0.7f * alpha),
            0.7f to core.copy(alpha = 0.25f * alpha),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
    // A faint prism fringe on the rim: present, never louder than the light itself.
    val rimRadius = radius * 1.06f
    drawCircle(
        brush = Brush.radialGradient(*fringeStops, center = center, radius = rimRadius),
        radius = rimRadius,
        center = center,
        alpha = 0.3f * alpha,
        blendMode = BlendMode.Plus,
    )
}

/** Thin concentric colour bands just outside the white core. */
private val fringeStops: Array<Pair<Float, Color>> = run {
    val colors = AirColors.IridescentFringe
    val first = 0.89f
    val step = (0.97f - first) / (colors.size - 1)
    arrayOf(0.86f to Color.Transparent) +
        colors.mapIndexed { index, color -> first + step * index to color }.toTypedArray() +
        arrayOf(1f to Color.Transparent)
}
