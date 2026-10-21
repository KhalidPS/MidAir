package com.kotlistine.transferingfilebyair.feature.pairing.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** How long Connect must be held: long enough to be deliberate, short enough not to feel like waiting. */
private const val HoldMillis = 1_000
private val ButtonSize = 88.dp
private val RingGap = 10.dp

/**
 * The white Connect button with a broadcast glyph. Press and hold: a ring fills around it (with rising haptic ticks)
 * and [onHeld] fires when it closes; letting go early springs the ring back. Dimmed and inert unless [enabled].
 */
@Composable
internal fun HoldToConnectButton(
    motion: PairingMotion,
    enabled: Boolean,
    onHeld: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnHeld by rememberUpdatedState(onHeld)
    val currentEnabled by rememberUpdatedState(enabled)
    val presence by animateFloatAsState(if (enabled) 1f else 0.35f, Motion.slideIn(), label = "connectPresence")

    // Ticks as the ring fills (not as it springs back): five steps, then a confirm when it closes.
    LaunchedEffect(motion) {
        var last = 0
        snapshotFlow { (motion.hold.value * 5f).toInt() }
            .distinctUntilChanged()
            .collect { step ->
                if (step > last) {
                    haptics.performHapticFeedback(
                        if (step >= 5) HapticFeedbackType.Confirm else HapticFeedbackType.SegmentFrequentTick,
                    )
                }
                last = step
            }
    }

    Box(
        modifier = modifier
            .size(ButtonSize + RingGap * 2)
            .onPlaced { motion.bloomCenter = it.boundsInRoot().center }
            .semantics {
                role = Role.Button
                contentDescription = "Connect. Press and hold."
                // TalkBack users can't hold; a double-tap connects.
                onClick { if (currentEnabled) currentOnHeld(); currentEnabled }
            }
            .pointerInput(motion) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    var filling: Job? = scope.launch {
                        motion.hold.animateTo(1f, tween((HoldMillis * (1f - motion.hold.value)).toInt(), easing = LinearEasing))
                        currentOnHeld()
                    }
                    waitForUpOrCancellation()
                    if (motion.hold.value < 1f) {
                        filling?.cancel()
                        filling = null
                        scope.launch { motion.hold.animateTo(0f, Motion.pop()) }
                    }
                }
            }
            .graphicsLayer {
                // Squeezes slightly as it is held, then lets go.
                val squeeze = 1f - 0.06f * motion.hold.value
                scaleX = squeeze
                scaleY = squeeze
                alpha = presence
            }
            .drawBehind {
                val ring = RingGap.toPx()
                val radius = size.minDimension / 2f - ring
                drawCircle(AirColors.OnSurface, radius = radius)
                drawBroadcastGlyph(radius * 0.5f)
                val progress = motion.hold.value
                if (progress > 0f) {
                    val strokeWidth = 4.dp.toPx()
                    val arc = radius + ring * 0.6f
                    drawArc(
                        color = AirColors.OnSurface,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(center.x - arc, center.y - arc),
                        size = Size(arc * 2, arc * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            },
    )
}

/** ((•)): a dot with two arcs on each side, in the background colour on the white button. */
private fun DrawScope.drawBroadcastGlyph(size: Float) {
    val ink = AirColors.Background
    val stroke = Stroke(width = size * 0.16f, cap = StrokeCap.Round)
    drawCircle(ink, radius = size * 0.16f, center = center)
    for (k in 1..2) {
        val r = size * (0.42f + 0.34f * (k - 1))
        val topLeft = Offset(center.x - r, center.y - r)
        val arcSize = Size(r * 2, r * 2)
        drawArc(ink, startAngle = -40f, sweepAngle = 80f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        drawArc(ink, startAngle = 140f, sweepAngle = 80f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    }
}
