package com.kotlistine.midair.feature.shelf.domain.gesture

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import kotlin.math.abs
import kotlin.test.Test

class HandSmootherTest {

    private val smoother = HandSmoother()

    @Test
    fun `the first position is taken as it is`() {
        assertThat(smoother.smooth(0.3f, 0.7f, atMillis = 0)).isEqualTo(0.3f to 0.7f)
    }

    @Test
    fun `a still hand's shaking is mostly smoothed away`() {
        var shownSpread = 0f
        var last = smoother.smooth(0.5f, 0.5f, atMillis = 0).first
        for (i in 1..40) {
            // The model's picture-to-picture shake: ±0.01 around a still hand.
            val raw = if (i % 2 == 0) 0.51f else 0.49f
            val shown = smoother.smooth(raw, 0.5f, atMillis = i * 50L).first
            if (i > 10) shownSpread = maxOf(shownSpread, abs(shown - last))
            last = shown
        }
        // Raw positions jump 0.02 every picture.
        assertThat(shownSpread).isLessThan(0.01f)
    }

    @Test
    fun `a moving hand is followed closely, not trailed`() {
        var shown = 0f
        // Across the picture in half a second: a quick move.
        for (i in 0..10) shown = smoother.smooth(i * 0.1f * 0.9f, 0.5f, atMillis = i * 50L).first
        val raw = 10 * 0.1f * 0.9f
        assertThat(shown).isBetween(raw - 0.08f, 1f)
    }

    @Test
    fun `a hand that was gone starts afresh where it reappears`() {
        smoother.smooth(0.1f, 0.1f, atMillis = 0)
        smoother.smooth(0.1f, 0.1f, atMillis = 50)

        assertThat(smoother.smooth(0.8f, 0.9f, atMillis = 1_000)).isEqualTo(0.8f to 0.9f)
    }

    @Test
    fun `positions stay on the picture`() {
        for (i in 0..10) {
            val (x, y) = smoother.smooth(minOf(1f, i * 0.2f), 0.5f, atMillis = i * 50L)
            assertThat(x).isBetween(0f, 1f)
            assertThat(y).isBetween(0f, 1f)
        }
    }
}
