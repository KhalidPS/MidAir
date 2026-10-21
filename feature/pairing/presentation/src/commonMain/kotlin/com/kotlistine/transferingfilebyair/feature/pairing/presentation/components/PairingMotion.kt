package com.kotlistine.transferingfilebyair.feature.pairing.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset

/** Turns of the edge glow per second: slow enough to read as flowing light, not spinning. */
private const val GlowTurnsPerSecond = 1f / 9f

/**
 * Everything on the pairing screen that moves: the glow's turn, the hold on Connect, and the bloom that bursts from
 * the button when the phones pair. Values are read in the draw phase only.
 */
@Stable
class PairingMotion internal constructor() {
    /** The edge glow's turn, 0..1, wrapping. */
    internal val glowPhase = Animatable(0f)

    /** How far Connect has been held, 0..1. */
    internal val hold = Animatable(0f)

    /** The pairing bloom: 0 → 1 as it floods out of the button. */
    internal val bloom = Animatable(0f)
    internal val bloomFade = Animatable(1f)

    /** Centre of the Connect button on screen, where the bloom starts. */
    internal var bloomCenter = Offset.Unspecified

    internal suspend fun turnGlowForever(frameSeconds: suspend () -> Float) {
        while (true) {
            val dt = frameSeconds()
            glowPhase.snapTo((glowPhase.value + dt * GlowTurnsPerSecond) % 1f)
        }
    }

    /** White light floods out of the button (~0.6 s), then fades (~0.5 s). Suspends until it is over. */
    suspend fun burst() {
        bloomFade.snapTo(1f)
        bloom.snapTo(0f)
        bloom.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        bloomFade.animateTo(0f, tween(500, easing = FastOutLinearInEasing))
    }
}

@Composable
fun rememberPairingMotion(): PairingMotion = remember { PairingMotion() }
