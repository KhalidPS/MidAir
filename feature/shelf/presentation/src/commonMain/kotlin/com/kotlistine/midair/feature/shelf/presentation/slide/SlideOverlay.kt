package com.kotlistine.midair.feature.shelf.presentation.slide

import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kotlistine.midair.core.designsystem.theme.AirColors
import com.kotlistine.midair.core.designsystem.theme.AirDimens
import com.kotlistine.midair.core.transfer.domain.ExitEdge
import com.kotlistine.midair.feature.shelf.presentation.components.FilePreview
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private val LiftedElevation = 28.dp

/**
 * The carried card. Laid out once at the slot's exact pixel size; every frame only changes its layer transform.
 * Put it in a full-screen layer so the window edge is what clips it, at the layer's absolute top-left (also in
 * right-to-left layouts): its offsets are in overlay pixels.
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
        // A playing video moves into the copy while it is in the air.
        isVideoActive = state.isAloft,
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
    // Falls off like light, not like a lit ball: bright near the source, a long soft tail.
    drawCircle(
        brush = Brush.radialGradient(
            0f to core.copy(alpha = 0.85f * alpha),
            0.3f to core.copy(alpha = 0.55f * alpha),
            0.6f to core.copy(alpha = 0.2f * alpha),
            0.85f to core.copy(alpha = 0.05f * alpha),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
    // A soft prism fringe on the rim: present, never louder than the light itself.
    val rimRadius = radius * 1.06f
    drawCircle(
        brush = Brush.radialGradient(*fringeStops, center = center, radius = rimRadius),
        radius = rimRadius,
        center = center,
        alpha = 0.22f * alpha,
        blendMode = BlendMode.Plus,
    )
}

/**
 * The light a card brings with it, rippling out across the screen from where it came in: a ring with a prism rim
 * that sweeps past every corner, leaving the screen as it was behind it (the reference's laptop side).
 * [wave] is read in the draw phase only.
 */
@Composable
internal fun ArrivalWave(wave: () -> Wave?, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind { wave()?.let { drawWave(it) } },
    )
}

private fun DrawScope.drawWave(wave: Wave) {
    val t = wave.progress
    if (t <= 0f || t >= 1f) return
    val origin = Offset(if (wave.edge == ExitEdge.RIGHT) size.width else 0f, wave.centerY)
    val band = size.width * 0.3f
    // Far enough that the whole band has passed the farthest corner when it ends.
    val reach = hypot(size.width, max(origin.y, size.height - origin.y)) + band
    val start = size.width * 0.4f
    val radius = start + (reach - start) * LinearOutSlowInEasing.transform(t)
    val core = AirColors.BloomCore

    // The flash the card arrives in, clearing quickly from the inside out.
    val flash = (1f - t / 0.4f).coerceIn(0f, 1f)
    if (flash > 0f) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to core.copy(alpha = 0.5f * flash),
                0.75f to core.copy(alpha = 0.3f * flash),
                1f to Color.Transparent,
                center = origin,
                radius = radius,
            ),
            radius = radius,
            center = origin,
        )
    }

    // The travelling ring: soft white light with the prism colours on its leading edge, fading as it spreads.
    val outer = radius + band * 0.1f
    drawCircle(
        brush = Brush.radialGradient(*waveStops(inner = (radius - band) / outer, crest = radius / outer), center = origin, radius = outer),
        radius = outer,
        center = origin,
        alpha = 0.8f * (1f - t) * (1f - t * 0.5f),
        blendMode = BlendMode.Plus,
    )
}

/** Clear inside, a white glow building toward the rim, then the prism bands (inner first) and clear again. */
private fun waveStops(inner: Float, crest: Float): Array<Pair<Float, Color>> {
    val start = inner.coerceIn(0f, crest * 0.5f)
    val span = crest - start
    val glow = AirColors.BloomCore.copy(alpha = 0.35f)
    val colors = AirColors.IridescentFringe.map { lerp(it, Color.White, 0.3f).copy(alpha = 0.75f) }
    val bandStart = start + span * 0.55f
    val step = (crest - bandStart) / (colors.size - 1)
    return arrayOf(start to Color.Transparent, start + span * 0.4f to glow) +
        colors.mapIndexed { index, color -> bandStart + step * index to color }.toTypedArray() +
        arrayOf(1f to Color.Transparent)
}

/** Concentric colour bands just outside the white core, softened at both sides. */
private val fringeStops: Array<Pair<Float, Color>> = run {
    val colors = AirColors.IridescentFringe.map { lerp(it, Color.White, 0.25f) }
    val first = 0.84f
    val step = (0.97f - first) / (colors.size - 1)
    arrayOf(0.78f to Color.Transparent) +
        colors.mapIndexed { index, color -> first + step * index to color }.toTypedArray() +
        arrayOf(1f to Color.Transparent)
}
