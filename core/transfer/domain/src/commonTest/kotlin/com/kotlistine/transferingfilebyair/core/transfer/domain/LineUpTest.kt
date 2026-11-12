package com.kotlistine.transferingfilebyair.core.transfer.domain

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class LineUpTest {

    /** The finger leaves the left phone (A) through its right edge, 50 mm down, at 200 mm/s. */
    private val leftLeaving = EdgeCrossing(
        leaving = true,
        edge = ExitEdge.RIGHT,
        alongEdgeMm = 50f,
        atMillis = 1_000,
        acrossMmPerS = 200f,
        alongEdgeMmPerS = 0f,
    )

    /** ...and arrives on the right phone (B) 50 ms later, 55 mm down. B's clock runs 300 ms ahead of A's. */
    private val rightArriving = EdgeCrossing(
        leaving = false,
        edge = ExitEdge.LEFT,
        alongEdgeMm = 55f,
        atMillis = 1_350,
        acrossMmPerS = 200f,
        alongEdgeMmPerS = 0f,
    )

    @Test
    fun `the phone the swipe left faces the other with its exit edge, and both measure the same gap`() {
        val onLeft = lineUp(mine = leftLeaving, theirs = rightArriving, peerClockAheadMillis = 300)
        val onRight = lineUp(mine = rightArriving, theirs = leftLeaving, peerClockAheadMillis = -300)

        assertThat(onLeft).isNotNull().given {
            assertThat(it.peerSide).isEqualTo(ExitEdge.RIGHT)
            assertThat(it.gapMm).isCloseTo(10f, Tolerance)
        }
        assertThat(onRight).isNotNull().given {
            assertThat(it.peerSide).isEqualTo(ExitEdge.LEFT)
            assertThat(it.gapMm).isCloseTo(10f, Tolerance)
        }
    }

    @Test
    fun `each phone turns the other's heights into its own`() {
        // The same point is 5 mm further down on the right phone, so its screen sits 5 mm higher.
        val onLeft = lineUp(mine = leftLeaving, theirs = rightArriving, peerClockAheadMillis = 300)!!
        val onRight = lineUp(mine = rightArriving, theirs = leftLeaving, peerClockAheadMillis = -300)!!

        assertThat(onLeft.verticalOffsetMm).isCloseTo(-5f, Tolerance)
        assertThat(onRight.verticalOffsetMm).isCloseTo(5f, Tolerance)
    }

    @Test
    fun `drifting down while crossing the gap isn't taken for an offset`() {
        val drifting = lineUp(
            mine = leftLeaving.copy(alongEdgeMmPerS = 100f),
            theirs = rightArriving.copy(alongEdgeMmPerS = 100f),
            peerClockAheadMillis = 300,
        )!!

        // 100 mm/s for 50 ms is the 5 mm the finger moved down.
        assertThat(drifting.verticalOffsetMm).isCloseTo(0f, Tolerance)
    }

    @Test
    fun `without comparing clocks the gap stays at its default`() {
        val lined = lineUp(mine = leftLeaving, theirs = rightArriving, peerClockAheadMillis = null)!!

        assertThat(lined.gapMm).isEqualTo(SlideLink.DefaultGapMm)
        assertThat(lined.peerSide).isEqualTo(ExitEdge.RIGHT)
    }

    @Test
    fun `a slightly early arrival counts as the smallest gap`() {
        val lined = lineUp(mine = leftLeaving, theirs = rightArriving.copy(atMillis = 1_270), peerClockAheadMillis = 300)!!

        assertThat(lined.gapMm).isEqualTo(MinGapMm)
    }

    @Test
    fun `crossings that can't be one swipe onto the other screen don't line anything up`() {
        assertThat(lineUp(leftLeaving, rightArriving.copy(leaving = true), 300)).isNull()
        assertThat(lineUp(leftLeaving, rightArriving.copy(edge = ExitEdge.RIGHT), 300)).isNull()
        assertThat(lineUp(leftLeaving, rightArriving.copy(atMillis = 4_000), 300)).isNull()
        assertThat(lineUp(leftLeaving, rightArriving.copy(alongEdgeMm = 400f), 300)).isNull()
    }

    @Test
    fun `the clocks are compared on the quickest round trip`() {
        val samples = listOf(
            ClockSample(sentAtMillis = 0, answeredAtMillis = 900, receivedAtMillis = 400),
            ClockSample(sentAtMillis = 1_000, answeredAtMillis = 1_320, receivedAtMillis = 1_040),
            ClockSample(sentAtMillis = 2_000, answeredAtMillis = 2_500, receivedAtMillis = 2_200),
        )

        // Answered 320 ms after sending, by a clock that reads 300 more at the trip's midpoint.
        assertThat(peerClockAheadMillis(samples)).isEqualTo(300L)
        assertThat(peerClockAheadMillis(emptyList())).isNull()
    }

    @Test
    fun `phones whose densities disagree measure the swipe at different speeds, and the line-up corrects for it`() {
        // B reports 5 % too few pixels per inch, so its millimetres come out 5 % long: it measures 210 mm/s.
        val fastB = rightArriving.copy(acrossMmPerS = 210f)

        val onLeft = lineUp(mine = leftLeaving, theirs = fastB, peerClockAheadMillis = 300)!!
        val onRight = lineUp(mine = fastB, theirs = leftLeaving, peerClockAheadMillis = -300)!!

        // A turns B's millimetres into its own, and B the other way round.
        assertThat(onLeft.peerMmScale).isCloseTo(200f / 210f, 0.001f)
        assertThat(onRight.peerMmScale).isCloseTo(210f / 200f, 0.001f)
    }

    @Test
    fun `crossings too far apart in speed to be a density error leave the millimetres alone`() {
        val lined = lineUp(mine = leftLeaving, theirs = rightArriving.copy(acrossMmPerS = 300f), peerClockAheadMillis = 300)

        assertThat(lined).isNotNull().given { assertThat(it.peerMmScale).isEqualTo(1f) }
    }

    @Test
    fun `a slow swipe is not used to compare densities`() {
        val lined = lineUp(
            mine = leftLeaving.copy(acrossMmPerS = 30f),
            theirs = rightArriving.copy(acrossMmPerS = 31f),
            peerClockAheadMillis = 300,
        )

        assertThat(lined).isNotNull().given { assertThat(it.peerMmScale).isEqualTo(1f) }
    }

}

private const val Tolerance = 0.01f
