package com.kotlistine.transferingfilebyair.feature.pairing.presentation.lineup

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import kotlin.test.Test

class SwipeTrackTest {

    /** A swipe moving 8 px right and 1 px down every 4 ms (2000 px/s, 250 px/s), from [startX]. */
    private fun swipe(startX: Float, steps: Int, dxPerStep: Float = 8f): List<TouchSample> =
        (0..steps).map { i -> TouchSample(x = startX + dxPerStep * i, y = 400f + i, uptimeMillis = 1_000L + 4L * i) }

    private fun crossing(samples: List<TouchSample>) =
        edgeCrossing(samples, widthPx = 1_080f, edgeZonePx = 100f, minSpeedPxPerS = 500f)

    @Test
    fun `a swipe that runs off the edge left through it, a moment after it was last felt`() {
        // Ends at x = 1060: 20 px short of the edge, which it reaches 10 ms later.
        val left = crossing(swipe(startX = 500f, steps = 70))

        assertThat(left).isNotNull().given {
            assertThat(it.leaving).isEqualTo(true)
            assertThat(it.edge).isEqualTo(ExitEdge.RIGHT)
            assertThat(it.millisFromLast).isEqualTo(10L)
            assertThat(it.acrossPxPerS).isCloseTo(2_000f, 1f)
            assertThat(it.alongEdgePx).isCloseTo(472.5f, 0.5f)
        }
    }

    @Test
    fun `a swipe that starts at the edge came in through it, before it was first felt`() {
        // Starts 10 px in, moving inward at 2000 px/s: it crossed 5 ms before, and lasted 120 ms after.
        val came = crossing(swipe(startX = 10f, steps = 30))

        assertThat(came).isNotNull().given {
            assertThat(it.leaving).isEqualTo(false)
            assertThat(it.edge).isEqualTo(ExitEdge.LEFT)
            assertThat(it.millisFromLast).isEqualTo(-125L)
            assertThat(it.alongEdgePx).isCloseTo(398.75f, 0.5f)
        }
    }

    @Test
    fun `a swipe that comes in from the right moves left`() {
        val came = crossing(swipe(startX = 1_070f, steps = 30, dxPerStep = -8f))

        assertThat(came?.edge).isEqualTo(ExitEdge.RIGHT)
        assertThat(came?.leaving).isEqualTo(false)
    }

    @Test
    fun `a swipe that stays inside, or crawls to the edge, crosses nothing`() {
        assertThat(crossing(swipe(startX = 300f, steps = 40))).isNull()
        assertThat(crossing(swipe(startX = 950f, steps = 40, dxPerStep = 1f))).isNull()
        assertThat(crossing(swipe(startX = 500f, steps = 0))).isNull()
    }
}
