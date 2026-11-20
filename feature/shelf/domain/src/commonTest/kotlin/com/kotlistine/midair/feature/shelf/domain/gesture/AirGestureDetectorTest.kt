package com.kotlistine.midair.feature.shelf.domain.gesture

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import kotlin.test.Test

class AirGestureDetectorTest {

    private val detector = AirGestureDetector(holdMillis = 200L, memoryMillis = 1_500L, minHandSize = 0.18f)
    private var now = 0L

    /** Feeds [pose] for [millis], a frame every 100 ms, and returns the gestures it completed. */
    private fun hold(pose: HandPose, millis: Long, size: Float = 0.4f, x: Float = 0.3f, y: Float = 0.6f): List<AirGesture> {
        val gestures = mutableListOf<AirGesture>()
        val end = now + millis
        while (now < end) {
            detector.onFrame(HandFrame(pose, x, y, size, atMillis = now))?.let { gestures += it }
            now += 100
        }
        return gestures
    }

    @Test
    fun `an open hand closing into a fist is a grab, where the fist is`() {
        assertThat(hold(HandPose.OPEN, 400)).containsExactly(AirGesture.Open(0.3f, 0.6f, 0.4f))

        assertThat(hold(HandPose.FIST, 400, x = 0.7f, y = 0.2f)).containsExactly(AirGesture.Grab(0.7f, 0.2f, 0.4f))
    }

    @Test
    fun `a fist opening is a release`() {
        hold(HandPose.FIST, 400)

        assertThat(hold(HandPose.OPEN, 400)).containsExactly(AirGesture.Release(0.3f, 0.6f, 0.4f))
    }

    @Test
    fun `a fist without an open hand before it grabs nothing`() {
        assertThat(hold(HandPose.FIST, 1_000)).isEmpty()
    }

    @Test
    fun `a single misread picture doesn't count as a pose`() {
        hold(HandPose.OPEN, 400)

        assertThat(hold(HandPose.FIST, 100) + hold(HandPose.OPEN, 400)).isEmpty()
    }

    @Test
    fun `the open hand is remembered while the model loses the closing hand for a moment`() {
        hold(HandPose.OPEN, 400)
        hold(HandPose.NONE, 300)
        hold(HandPose.OTHER, 200)

        assertThat(hold(HandPose.FIST, 300)).containsExactly(AirGesture.Grab(0.3f, 0.6f, 0.4f))
    }

    @Test
    fun `an open hand from long ago doesn't make a later fist a grab`() {
        hold(HandPose.OPEN, 400)
        hold(HandPose.NONE, 3_000)

        assertThat(hold(HandPose.FIST, 400)).isEmpty()
    }

    @Test
    fun `hands far from the phone are ignored`() {
        hold(HandPose.OPEN, 400, size = 0.1f)

        assertThat(hold(HandPose.FIST, 400, size = 0.1f)).isEmpty()
    }

    @Test
    fun `one open hand starts only one grab`() {
        hold(HandPose.OPEN, 400)
        hold(HandPose.FIST, 400)
        hold(HandPose.OTHER, 200)

        assertThat(hold(HandPose.FIST, 400)).isEmpty()
    }

    @Test
    fun `after a release the hand must leave the view before it grabs again`() {
        hold(HandPose.FIST, 400)
        hold(HandPose.OPEN, 400)

        assertThat(hold(HandPose.FIST, 400)).isEmpty()

        hold(HandPose.NONE, 400)
        hold(HandPose.OPEN, 400)
        assertThat(hold(HandPose.FIST, 400)).containsExactly(AirGesture.Grab(0.3f, 0.6f, 0.4f))
    }

    @Test
    fun `an open hand coming into view is reported once, even without a fist before it`() {
        hold(HandPose.NONE, 400)

        assertThat(hold(HandPose.OPEN, 1_000)).containsExactly(AirGesture.Open(0.3f, 0.6f, 0.4f))
    }

    @Test
    fun `one stray picture in the middle of a pose doesn't restart its wait`() {
        hold(HandPose.OPEN, 400)
        // The fist closes at 400 ms; the model misreads one picture of it at 600 ms.
        hold(HandPose.FIST, 200)
        hold(HandPose.OTHER, 100)

        // Steady since 400 ms. Without the stray picture forgiven, the fist would only count 200 ms after it.
        assertThat(hold(HandPose.FIST, 100)).containsExactly(AirGesture.Grab(0.3f, 0.6f, 0.4f))
    }

    @Test
    fun `a hand that must leave the view first reports nothing until it does`() {
        hold(HandPose.OPEN, 400)
        detector.requireHandAway()

        assertThat(hold(HandPose.FIST, 400) + hold(HandPose.OPEN, 400)).isEmpty()

        hold(HandPose.NONE, 400)
        assertThat(hold(HandPose.OPEN, 400)).containsExactly(AirGesture.Open(0.3f, 0.6f, 0.4f))
    }

    @Test
    fun `reset forgets the hand seen so far`() {
        hold(HandPose.OPEN, 400)
        detector.reset()

        assertThat(hold(HandPose.FIST, 400)).isEmpty()
    }
}
