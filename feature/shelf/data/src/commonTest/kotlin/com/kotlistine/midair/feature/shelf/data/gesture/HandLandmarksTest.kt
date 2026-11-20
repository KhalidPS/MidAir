package com.kotlistine.midair.feature.shelf.data.gesture

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import com.kotlistine.midair.feature.shelf.domain.gesture.HandPose
import kotlin.test.Test

class HandLandmarksTest {

    @Test
    fun `four straightened fingers are an open hand`() {
        val frame = handFrameOf(hand(Finger.STRETCHED), width = 640, height = 480, atMillis = 7)

        assertThat(frame.pose).isEqualTo(HandPose.OPEN)
        assertThat(frame.atMillis).isEqualTo(7L)
    }

    @Test
    fun `four fingers curled into the palm are a fist`() {
        assertThat(handFrameOf(hand(Finger.CURLED), 640, 480, 0).pose).isEqualTo(HandPose.FIST)
    }

    @Test
    fun `a fist with one finger half bent is still a fist`() {
        val points = hand(Finger.CURLED, Finger.CURLED, Finger.CURLED, Finger.HALF)

        assertThat(handFrameOf(points, 640, 480, 0).pose).isEqualTo(HandPose.FIST)
    }

    @Test
    fun `a pointing hand is neither open nor a fist`() {
        val points = hand(Finger.STRETCHED, Finger.CURLED, Finger.CURLED, Finger.CURLED)

        assertThat(handFrameOf(points, 640, 480, 0).pose).isEqualTo(HandPose.OTHER)
    }

    @Test
    fun `too few points mean no hand`() {
        assertThat(handFrameOf(hand(Finger.STRETCHED).take(5), 640, 480, 0).pose).isEqualTo(HandPose.NONE)
    }

    @Test
    fun `position and size are fractions of the picture`() {
        val frame = handFrameOf(hand(Finger.STRETCHED), width = 640, height = 480, atMillis = 0)

        // The hand spans x 68..125 (thumb tip to pinky) and y 130..300 (fingertips to wrist) in a 640 × 480 picture.
        assertThat(frame.x).isCloseTo(96.5f / 640f, 0.001f)
        assertThat(frame.y).isCloseTo(215f / 480f, 0.001f)
        assertThat(frame.size).isCloseTo(170f / 480f, 0.001f)
    }

    private enum class Finger { STRETCHED, HALF, CURLED }

    /** A hand held up with the wrist at (100, 300), fingers pointing up, each one [fingers] (repeated for all four). */
    private fun hand(vararg fingers: Finger): List<HandPoint> {
        val shapes = if (fingers.size == 1) List(4) { fingers[0] } else fingers.toList()
        val points = MutableList(HandPointCount) { HandPoint(100f, 300f) }
        // The thumb, off to the side.
        for (i in 1..4) points[i] = HandPoint(100f - 8f * i, 280f - 10f * i)
        shapes.forEachIndexed { finger, shape ->
            val x = 80f + 15f * finger
            val first = 5 + finger * 4
            val ys = when (shape) {
                // Knuckle, middle joint, last joint, tip.
                Finger.STRETCHED -> listOf(200f, 170f, 150f, 130f)
                Finger.HALF -> listOf(200f, 170f, 160f, 160f)
                Finger.CURLED -> listOf(200f, 170f, 190f, 215f)
            }
            ys.forEachIndexed { i, y -> points[first + i] = HandPoint(x, y) }
        }
        return points
    }
}
