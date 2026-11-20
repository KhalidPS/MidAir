package com.kotlistine.midair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.prop
import kotlin.test.Test

class SlideMoveTimingsTest {

    private val timings = SlideMoveTimings().apply { start("card") }

    /** Moves sent every 10 ms from peer time 1_000, arriving at these local times. */
    private fun arriving(vararg receivedAt: Long) {
        receivedAt.forEachIndexed { i, at -> timings.add(seq = i + 1, receivedAtMillis = at, sentAtMillis = 1_000L + i * 10) }
    }

    @Test
    fun `nothing is summed up before the first move`() {
        assertThat(timings.summary(peerClockAheadMillis = 0)).isNull()
    }

    @Test
    fun `arrival gaps show how unevenly moves came in`() {
        arriving(5_000, 5_010, 5_020, 5_080, 5_090)

        assertThat(timings.summary(null)).isNotNull().prop(MoveTimings::arrivalGaps)
            .isEqualTo(Spread(p50 = 10, p95 = 60, max = 60))
    }

    @Test
    fun `lateness is measured from the quickest move, whatever the clocks`() {
        // Sent 10 ms apart; the third is held up 50 ms on the way.
        arriving(5_000, 5_010, 5_070, 5_030)

        val summary = timings.summary(null)!!
        assertThat(summary.sentGaps).isEqualTo(Spread(p50 = 10, p95 = 10, max = 10))
        assertThat(summary.late).isEqualTo(Spread(p50 = 0, p95 = 50, max = 50))
        assertThat(summary.delay).isNull()
    }

    @Test
    fun `the delay uses the peer's clock lead`() {
        // The peer's clock is 3_980 ms behind: a move sent at its 1_000 left at this phone's 4_980.
        arriving(5_000, 5_030)

        assertThat(timings.summary(peerClockAheadMillis = -3_980)).isNotNull().prop(MoveTimings::delay)
            .isEqualTo(Spread(p50 = 20, p95 = 40, max = 40))
    }

    @Test
    fun `an older sender without timestamps still gets its arrival gaps`() {
        timings.add(seq = 1, receivedAtMillis = 5_000, sentAtMillis = 0)
        timings.add(seq = 2, receivedAtMillis = 5_016, sentAtMillis = 0)

        val summary = timings.summary(peerClockAheadMillis = 0)!!
        assertThat(summary.arrivalGaps).isEqualTo(Spread(p50 = 16, p95 = 16, max = 16))
        assertThat(summary.sentGaps).isNull()
        assertThat(summary.late).isNull()
        assertThat(summary.delay).isNull()
    }

    @Test
    fun `a move older than one already here is counted but not measured`() {
        timings.add(seq = 2, receivedAtMillis = 5_000, sentAtMillis = 1_010)
        timings.add(seq = 1, receivedAtMillis = 5_005, sentAtMillis = 1_000)

        val summary = timings.summary(null)!!
        assertThat(summary.moves).isEqualTo(1)
        assertThat(summary.outOfOrder).isEqualTo(1)
    }

    @Test
    fun `a new lift starts from nothing`() {
        arriving(5_000, 5_010)
        timings.start("next")

        assertThat(timings.summary(null)).isNull()
        assertThat(timings.itemId).isEqualTo("next")
    }

    @Test
    fun `the log line reads every part`() {
        arriving(5_000, 5_010)

        assertThat(timings.summary(null)!!.describe()).isEqualTo(
            "Moves: 2 (0 out of order), arrival gaps p50 10 / p95 10 / max 10 ms, " +
                "sent gaps p50 10 / p95 10 / max 10 ms, late p50 0 / p95 0 / max 0 ms, delay n/a",
        )
    }
}
