package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import kotlin.math.ceil
import kotlin.math.exp

/** Until this many moves have been measured, the playback runs this far behind. */
private const val DefaultDelayMillis = 40.0
private const val MinMeasuredMoves = 8
private const val MinDelayMillis = 12.0
private const val MaxDelayMillis = 150.0

/** A longer pause between two moves is the card resting, not the pace they are sent at. */
private const val MaxIntervalMillis = 50.0

/** How many recent moves the delay is measured over (a slide or two). */
private const val LatenessWindow = 120

/** No further ahead than this is predicted, however slow the link: past it a guess is worse than a lag. */
private const val MaxAheadMillis = 200.0

/** With no move to show, the card goes on along its last velocity, slowing like this (so a real stop doesn't overshoot). */
private const val GapSlowdownMillis = 150.0

/** How quickly a correction (a quicker move, a new delay, a move after a gap) fades into the path, never a snap. */
private const val CorrectionMillis = 80.0

/** Moves older than this behind the one being shown are no longer needed. */
private const val HistoryMillis = 500.0
private const val MaxHistory = 64

/**
 * One move of the peer's card, in this screen's pixels: where it was ([x], [y], its top-left) and how fast it went
 * (pixels per second), at [sentAtMillis] by the sender's clock. [receivedAtMillis] is by this phone's clock; the two
 * clocks needn't agree, only each one with itself.
 */
internal data class MoveSample(
    val sentAtMillis: Double,
    val receivedAtMillis: Double,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val tilt: Float,
    val arrival: Float,
)

/** Where to draw the card on one frame. */
internal data class Pose(val x: Float, val y: Float, val vx: Float, val vy: Float, val tilt: Float, val arrival: Float)

/**
 * Plays the peer's card moves back at an even pace, however unevenly they arrive (the way online games show another
 * player moving): each frame shows the card where it was a short, fixed time ago by the sender's clock (interpolated
 * between the two moves around then), then projects that forward along its velocity by the same time plus the trip
 * between the phones, so it isn't behind the finger.
 *
 * - The time ago ([delayMillis]) is the 95th percentile of how late moves come in (compared with the quickest), plus
 *   the time between two moves, so almost every frame has a move on each side of it. It grows by itself on a slow, uneven link (Bluetooth).
 * - Which sender time is "now" comes from the quickest move of the slide: no clock comparison needed.
 * - With no move after the time shown (a gap on the link), the card goes on along its last velocity, slowing down.
 * - Whenever the path changes under the card (a move arrives after a gap, a quicker move shifts the timing), the jump
 *   is taken up by a correction that fades out over [CorrectionMillis]: the card never snaps.
 *
 * Pure arithmetic with times in milliseconds: the screen feeds it moves and asks for a pose every frame.
 */
internal class MovePlayback {

    private val samples = ArrayList<MoveSample>()

    /** How much later than the slide's quickest move each recent move came in. Kept across slides. */
    private val lateness = ArrayDeque<Double>()

    /** The quickest (received − sent) of this slide: the sender's "now" is this phone's now minus it. */
    private var quickest = Double.POSITIVE_INFINITY

    /** The usual time between two moves, by the sender's clock. */
    private var intervalMillis = 0.0

    /** The trip from the sender to here, predicted away on top of the delay. */
    private var leadMillis = 0.0

    var delayMillis = DefaultDelayMillis
        private set

    private var correctionX = 0f
    private var correctionY = 0f
    private var lastFrameMillis: Double? = null

    /** A new card: its moves start from nothing (how late moves come in is still known from before). */
    fun start() {
        samples.clear()
        quickest = Double.POSITIVE_INFINITY
        correctionX = 0f
        correctionY = 0f
        lastFrameMillis = null
    }

    /** Adds the next move. [leadMillis] is the one-way trip between the phones (half the round trip). */
    fun add(sample: MoveSample, leadMillis: Double) {
        val shownAt = lastFrameMillis
        val before = shownAt?.let { path(it) }

        // Moves come in order (older ones are dropped before this); one stamped like the last replaces it.
        val previous = samples.lastOrNull()
        if (previous != null && sample.sentAtMillis <= previous.sentAtMillis) samples.removeAt(samples.lastIndex)
        if (previous != null && sample.sentAtMillis > previous.sentAtMillis) {
            val interval = (sample.sentAtMillis - previous.sentAtMillis).coerceAtMost(MaxIntervalMillis)
            intervalMillis = if (intervalMillis == 0.0) interval else intervalMillis * 0.9 + interval * 0.1
        }
        samples += sample
        this.leadMillis = leadMillis
        val transit = sample.receivedAtMillis - sample.sentAtMillis
        if (transit < quickest) quickest = transit
        lateness.addLast(transit - quickest)
        if (lateness.size > LatenessWindow) lateness.removeFirst()
        if (lateness.size >= MinMeasuredMoves) {
            delayMillis = (percentile95(lateness) + intervalMillis).coerceIn(MinDelayMillis, MaxDelayMillis)
        }
        trim()

        if (before != null && shownAt != null) {
            val after = path(shownAt)
            correctionX += before.x - after.x
            correctionY += before.y - after.y
        }
    }

    /** Where to draw the card at [nowMillis] (this phone's clock); null before the first move. */
    fun poseAt(nowMillis: Double): Pose? {
        if (samples.isEmpty()) return null
        lastFrameMillis?.let { last ->
            val fade = exp(-(nowMillis - last).coerceAtLeast(0.0) / CorrectionMillis).toFloat()
            correctionX *= fade
            correctionY *= fade
        }
        lastFrameMillis = nowMillis
        val pose = path(nowMillis)
        return pose.copy(x = pose.x + correctionX, y = pose.y + correctionY)
    }

    /** The card's path without corrections: shown [delayMillis] ago, then projected forward. */
    private fun path(nowMillis: Double): Pose {
        val shown = nowMillis - quickest - delayMillis
        val pose = at(shown)
        val ahead = ((delayMillis + leadMillis).coerceAtMost(MaxAheadMillis) / 1000.0).toFloat()
        return pose.copy(x = pose.x + pose.vx * ahead, y = pose.y + pose.vy * ahead)
    }

    /** The card at sender time [t]: between the moves around it, or carried on past the last one. */
    private fun at(t: Double): Pose {
        val first = samples.first()
        if (t <= first.sentAtMillis) return first.pose()
        val last = samples.last()
        if (t >= last.sentAtMillis) {
            val since = t - last.sentAtMillis
            val slowing = exp(-since / GapSlowdownMillis)
            // The distance covered at a speed that fades out: v·τ·(1 − e^(−t/τ)).
            val travelled = (GapSlowdownMillis * (1.0 - slowing) / 1000.0).toFloat()
            return Pose(
                x = last.x + last.vx * travelled,
                y = last.y + last.vy * travelled,
                vx = last.vx * slowing.toFloat(),
                vy = last.vy * slowing.toFloat(),
                tilt = last.tilt,
                arrival = last.arrival,
            )
        }
        val after = samples.indexOfFirst { it.sentAtMillis > t }
        val a = samples[after - 1]
        val b = samples[after]
        val f = ((t - a.sentAtMillis) / (b.sentAtMillis - a.sentAtMillis)).toFloat()
        return Pose(
            x = a.x + (b.x - a.x) * f,
            y = a.y + (b.y - a.y) * f,
            vx = a.vx + (b.vx - a.vx) * f,
            vy = a.vy + (b.vy - a.vy) * f,
            tilt = a.tilt + (b.tilt - a.tilt) * f,
            arrival = a.arrival + (b.arrival - a.arrival) * f,
        )
    }

    private fun trim() {
        val shownAt = lastFrameMillis ?: run {
            while (samples.size > MaxHistory) samples.removeAt(0)
            return
        }
        val oldest = shownAt - quickest - delayMillis - HistoryMillis
        // Keep one move before the oldest time that can still be shown, to interpolate from.
        while (samples.size > 2 && (samples[1].sentAtMillis < oldest || samples.size > MaxHistory)) samples.removeAt(0)
    }

    private fun MoveSample.pose() = Pose(x, y, vx, vy, tilt, arrival)
}

private fun percentile95(values: Collection<Double>): Double {
    val sorted = values.sorted()
    return sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
}
