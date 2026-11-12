package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.math.abs
import kotlin.test.Test

class MovePlaybackTest {

    private val playback = MovePlayback()

    /** The sender's clock runs this far ahead of the receiver's. */
    private val clockAhead = 5_000.0

    /** The quickest trip from sender to receiver. */
    private val oneWay = 20.0

    /** A card moving right at 1 000 px/s: where it is at the sender's [t]. */
    private fun xAt(t: Double): Float = ((t - clockAhead) / 1000.0 * 1000.0).toFloat()

    /** A move made at the receiver's time [madeAt] (sender time + clock lead), arriving [late] ms after the quickest. */
    private fun send(madeAt: Double, late: Double = 0.0, vx: Float = 1000f) {
        val sentAt = madeAt + clockAhead
        playback.add(
            MoveSample(
                sentAtMillis = sentAt,
                receivedAtMillis = madeAt + oneWay + late,
                x = xAt(sentAt),
                y = 0f,
                vx = vx,
                vy = 0f,
                tilt = 0f,
                arrival = 1f,
            ),
            leadMillis = oneWay,
        )
    }

    /** Unevenness of the link: how late each of the moves, sent every 10 ms, comes in. */
    private val jitter = listOf(0.0, 25.0, 3.0, 40.0, 8.0, 0.0, 30.0, 12.0, 5.0, 0.0)

    @Test
    fun `nothing is drawn before the first move`() {
        assertThat(playback.poseAt(0.0)).isNull()
    }

    /**
     * Plays moves made every 10 ms (receiver time, from 0) arriving at [arrivesAt] (by index), drawing a frame every
     * 8 ms after the moves that came by then. Returns each frame's time and where the card was drawn.
     */
    private fun play(moves: Int, untilMillis: Double, arrivesAt: (Int) -> Double): List<Pair<Double, Float>> {
        val made = (0 until moves).map { it * 10.0 }
        val order = made.indices.sortedBy { arrivesAt(it) }
        var next = 0
        val drawn = mutableListOf<Pair<Double, Float>>()
        var frame = 0.0
        while (frame < untilMillis) {
            while (next < order.size && arrivesAt(order[next]) <= frame) {
                val i = order[next++]
                send(made[i], late = arrivesAt(i) - made[i] - oneWay)
            }
            playback.poseAt(frame)?.let { drawn += frame to it.x }
            frame += 8.0
        }
        return drawn
    }

    @Test
    fun `a steady card plays back where it is now, however unevenly its moves came`() {
        val drawn = play(moves = 100, untilMillis = 1_000.0) { i -> i * 10.0 + oneWay + jitter[i % jitter.size] }
            .filter { it.first > 300.0 }

        // After the first few moves have set the delay, every frame shows the card where it really is...
        drawn.forEach { (at, x) -> assertThat(x).isCloseTo(xAt(at + clockAhead), 1.5f) }
        // ...and it moves the same distance every frame: an even pace.
        drawn.zipWithNext { a, b -> b.second - a.second }.forEach { assertThat(it).isCloseTo(8f, 0.5f) }
    }

    @Test
    fun `the delay follows how late moves come in`() {
        (0 until 40).forEach { i -> send(i * 10.0, late = jitter[i % jitter.size]) }

        // The 95th percentile of the lateness (the 40 ms ones), plus the 10 ms between two moves.
        assertThat(playback.delayMillis).isCloseTo(50.0, 0.01)
    }

    @Test
    fun `the delay keeps within its bounds on a very uneven link`() {
        (0 until 40).forEach { i -> send(i * 10.0, late = if (i % 3 == 0) 400.0 else 0.0) }

        assertThat(playback.delayMillis).isEqualTo(150.0)
    }

    @Test
    fun `during a gap the card goes on, slowing down, and goes no further than its momentum carries it`() {
        (0 until 20).forEach { i -> send(i * 10.0) }
        val lastX = xAt(190.0 + clockAhead)

        val soon = playback.poseAt(260.0)!!
        val later = playback.poseAt(2_000.0)!!

        assertThat(soon.vx).isLessThan(1000f)
        // At most v·τ (150 ms at 1 000 px/s) past the last move, plus nothing projected once it has stopped.
        assertThat(later.x).isBetween(lastX, lastX + 151f)
        assertThat(abs(later.vx)).isLessThan(1f)
    }

    @Test
    fun `moves resuming after a gap don't snap the card`() {
        // The moves made from 200 to 400 ms are held up on the link and all arrive at 420 ms.
        val drawn = play(moves = 100, untilMillis = 1_000.0) { i ->
            val madeAt = i * 10.0
            if (madeAt in 200.0..400.0) 420.0 else madeAt + oneWay
        }

        // Never more than a few frames' worth at once (1 000 px/s is 8 px a frame)...
        drawn.zipWithNext { a, b -> abs(b.second - a.second) }.forEach { assertThat(it).isLessThan(30f) }
        // ...and back on the real path once the moves flow again.
        val (at, x) = drawn.last()
        assertThat(x).isCloseTo(xAt(at + clockAhead), 2f)
    }

    @Test
    fun `an older sender, without timestamps, still plays back`() {
        (0 until 10).forEach { i ->
            playback.add(
                MoveSample(i * 10.0, i * 10.0, x = i * 10f, y = 0f, vx = 1000f, vy = 0f, tilt = 0f, arrival = 1f),
                leadMillis = 0.0,
            )
        }

        assertThat(playback.poseAt(95.0)).isNotNull()
    }

    @Test
    fun `a new card starts from its own moves`() {
        (0 until 10).forEach { i -> send(i * 10.0) }
        playback.poseAt(100.0)
        playback.start()

        assertThat(playback.poseAt(100.0)).isNull()
    }
}
