package com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture

import kotlin.math.PI
import kotlin.math.abs

/**
 * Smooths where the hand is, for drawing. The pose model's positions shake by a few pixels from picture to picture,
 * and arrive a little late. This is a One Euro filter: strong smoothing while the hand is nearly still (no shaking),
 * almost none while it moves fast (no trailing behind). The result is then pushed [leadMillis] ahead along the hand's
 * smoothed speed, to make up for part of the camera's delay.
 *
 * Positions are 0..1 across and down the picture, like [HandFrame]. Pure, so it is unit-tested and portable.
 */
class HandSmoother(
    /** Smoothing of a still hand, in Hz: lower is steadier but slower to start moving. */
    private val minCutoffHz: Float = 1.2f,
    /** How quickly the smoothing lets go as the hand speeds up (per unit of picture per second). */
    private val beta: Float = 6f,
    /** Smoothing of the speed itself, in Hz. */
    private val speedCutoffHz: Float = 1f,
    private val leadMillis: Long = 45L,
    /** A hand gone longer than this starts afresh where it reappears, instead of gliding over from where it left. */
    private val gapMillis: Long = 300L,
) {
    private val x = Axis()
    private val y = Axis()
    private var lastAt: Long? = null

    /** The smoothed (and slightly predicted) position for a hand seen at ([rawX], [rawY]) at [atMillis]. */
    fun smooth(rawX: Float, rawY: Float, atMillis: Long): Pair<Float, Float> {
        val previous = lastAt
        lastAt = atMillis
        if (previous == null || atMillis - previous > gapMillis || atMillis <= previous) {
            x.reset(rawX)
            y.reset(rawY)
            return rawX to rawY
        }
        val seconds = (atMillis - previous) / 1_000f
        val lead = leadMillis / 1_000f
        return x.next(rawX, seconds, lead) to y.next(rawY, seconds, lead)
    }

    /** The hand left the view: the next one starts afresh. */
    fun reset() {
        lastAt = null
    }

    private inner class Axis {
        private var value = 0f
        private var speed = 0f

        fun reset(at: Float) {
            value = at
            speed = 0f
        }

        fun next(raw: Float, seconds: Float, lead: Float): Float {
            val rawSpeed = (raw - value) / seconds
            speed += alpha(speedCutoffHz, seconds) * (rawSpeed - speed)
            val cutoff = minCutoffHz + beta * abs(speed)
            value += alpha(cutoff, seconds) * (raw - value)
            return (value + speed * lead).coerceIn(0f, 1f)
        }
    }

    private fun alpha(cutoffHz: Float, seconds: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / seconds)
    }
}
