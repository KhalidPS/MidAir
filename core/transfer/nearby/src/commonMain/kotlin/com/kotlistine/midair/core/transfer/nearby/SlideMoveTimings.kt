package com.kotlistine.midair.core.transfer.nearby

import kotlin.math.ceil

/**
 * How the moves of one card in the air reached this phone: how unevenly they came and how late. Fed with every move
 * from the lift to the landing or withdrawal, then summed up for the `AirSlide` log (smoother card half, C0).
 */
internal class SlideMoveTimings {

    /** The card being measured; null between slides. */
    var itemId: String? = null
        private set

    private val receivedAt = ArrayList<Long>()
    private val sentAt = ArrayList<Long>()
    private var lastSeq = -1
    private var outOfOrder = 0

    /** A new card was lifted: forget the last one. */
    fun start(itemId: String) {
        this.itemId = itemId
        receivedAt.clear()
        sentAt.clear()
        lastSeq = -1
        outOfOrder = 0
    }

    fun stop() {
        itemId = null
    }

    /**
     * One move of the card: [receivedAtMillis] by this phone's clock, [sentAtMillis] by the peer's (0 when the peer
     * doesn't say). A move older than one already here is only counted: the screen drops it too.
     */
    fun add(seq: Int, receivedAtMillis: Long, sentAtMillis: Long) {
        if (seq <= lastSeq) {
            outOfOrder++
            return
        }
        lastSeq = seq
        receivedAt += receivedAtMillis
        sentAt += sentAtMillis
    }

    /**
     * The slide so far, or null before its first move. [peerClockAheadMillis] (null when unknown) turns the peer's
     * timestamps into this phone's time, for the one-way delay.
     */
    fun summary(peerClockAheadMillis: Long?): MoveTimings? {
        if (receivedAt.isEmpty()) return null
        val stamped = sentAt.all { it > 0L }
        // Time on the way, in mixed clocks: how much later than the quickest move each one was doesn't depend on
        // how far apart the clocks are.
        val transit = if (stamped) receivedAt.indices.map { receivedAt[it] - sentAt[it] } else null
        return MoveTimings(
            moves = receivedAt.size,
            outOfOrder = outOfOrder,
            arrivalGaps = receivedAt.gaps().spread(),
            sentGaps = if (stamped) sentAt.gaps().spread() else null,
            late = transit?.let { all -> all.min().let { quickest -> all.map { it - quickest } } }?.spread(),
            delay = if (peerClockAheadMillis != null) transit?.map { it + peerClockAheadMillis }?.spread() else null,
        )
    }

    private fun List<Long>.gaps(): List<Long> = zipWithNext { a, b -> b - a }
}

/**
 * One slide's moves as they reached the receiver, in milliseconds. A part is null when it can't be told: gaps need
 * two moves, the rest needs the sender's timestamps, and [delay] also needs the two clocks compared.
 *
 * @property arrivalGaps time between two moves arriving here.
 * @property sentGaps time between two moves leaving the sender: how evenly it sent them.
 * @property late how much later than the slide's quickest move each move took to get here: the unevenness the link
 *   adds, and so how far behind an even playback must run to hide it.
 * @property delay the one-way trip of each move.
 */
internal data class MoveTimings(
    val moves: Int,
    val outOfOrder: Int,
    val arrivalGaps: Spread?,
    val sentGaps: Spread?,
    val late: Spread?,
    val delay: Spread?,
) {
    /** The `AirSlide` log line. */
    fun describe(): String = "Moves: $moves ($outOfOrder out of order), arrival gaps ${arrivalGaps.text()}, " +
        "sent gaps ${sentGaps.text()}, late ${late.text()}, delay ${delay.text()}"

    private fun Spread?.text(): String = this?.let { "p50 $p50 / p95 $p95 / max $max ms" } ?: "n/a"
}

internal data class Spread(val p50: Long, val p95: Long, val max: Long)

/** The median, the 95th percentile (nearest rank) and the largest; null when empty. */
internal fun List<Long>.spread(): Spread? {
    if (isEmpty()) return null
    val sorted = sorted()
    fun rank(p: Double) = sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    return Spread(p50 = rank(0.5), p95 = rank(0.95), max = sorted.last())
}
