package com.kotlistine.transferingfilebyair.core.transfer.domain

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** The gap a swipe can measure: screens can't overlap, and phones a hand apart aren't "next to each other". */
const val MinGapMm = 2f
const val MaxGapMm = 40f

/** No two phones side by side sit further apart vertically than this. */
private const val MaxOffsetMm = 150f

/** A finger crossing from one screen to the next takes well under this, even through a wide gap. */
private const val MaxCrossingMillis = 1_500L

/**
 * The two crossings of one swipe measure the same finger speed, each in its own phone's millimetres; their ratio says
 * how far the phones' densities disagree. Beyond this much it is more likely the finger sped up or slowed down.
 */
private const val MaxDensityMismatch = 0.1f

/** Slower crossings measure their speed too roughly to compare densities. */
private const val MinCalibrationMmPerS = 40f

/** Clock comparisons can be a little off; a crossing this much "before" its exit still counts (as the smallest gap). */
private const val ClockSlackMillis = 60L

/**
 * Where a line-up swipe crossed this device's screen edge: one finger slides from one screen onto the other, so one
 * device sees it leave ([leaving]) and the other sees it arrive.
 *
 * @property edge the edge the finger crossed.
 * @property alongEdgeMm where on that edge, down from the top of the screen.
 * @property atMillis when it was on the edge line, by this device's clock.
 * @property acrossMmPerS how fast it moved through the edge.
 * @property alongEdgeMmPerS how fast it moved along it, downward positive.
 */
data class EdgeCrossing(
    val leaving: Boolean,
    val edge: ExitEdge,
    val alongEdgeMm: Float,
    val atMillis: Long,
    val acrossMmPerS: Float,
    val alongEdgeMmPerS: Float,
)

/** One round-trip probe: sent and received by this device's clock, answered by the peer's. */
data class ClockSample(val sentAtMillis: Long, val answeredAtMillis: Long, val receivedAtMillis: Long)

/**
 * How far the peer's clock is ahead of this one's, from the quickest round trip (the one least skewed by delays on
 * the way), assuming it took as long each way. Null without samples.
 */
fun peerClockAheadMillis(samples: List<ClockSample>): Long? {
    val quickest = samples.minByOrNull { it.receivedAtMillis - it.sentAtMillis } ?: return null
    return quickest.answeredAtMillis - (quickest.sentAtMillis + quickest.receivedAtMillis) / 2
}

/**
 * How the two screens sit, from where one swipe crossed this screen's edge ([mine]) and the peer's ([theirs]):
 * this screen's edge that faces the peer, how far its picture is from the peer's (the gap: the finger's speed times
 * how long it took to get across), and how far up or down the peer's screen sits. Null when the two crossings can't
 * be one swipe from one screen onto the other (both leaving, the same side, too far apart in place or time).
 *
 * @param peerClockAheadMillis from [peerClockAheadMillis]; when unknown the gap stays at its default.
 */
fun lineUp(mine: EdgeCrossing, theirs: EdgeCrossing, peerClockAheadMillis: Long?): SlideLink? {
    // Side by side, the finger leaves one screen and enters the other through the facing (opposite) edge.
    if (mine.leaving == theirs.leaving || mine.edge == theirs.edge) return null
    val scale = peerMmScale(mine, theirs)
    // The peer's crossing on this phone's clock and in its millimetres.
    val theirsHere = theirs.copy(
        atMillis = theirs.atMillis - (peerClockAheadMillis ?: 0L),
        alongEdgeMm = theirs.alongEdgeMm * scale,
        acrossMmPerS = theirs.acrossMmPerS * scale,
        alongEdgeMmPerS = theirs.alongEdgeMmPerS * scale,
    )
    val (from, to) = if (mine.leaving) mine to theirsHere else theirsHere to mine
    val crossingMillis = if (peerClockAheadMillis != null) to.atMillis - from.atMillis else 0L
    if (crossingMillis < -ClockSlackMillis || crossingMillis > MaxCrossingMillis) return null
    val seconds = crossingMillis.coerceAtLeast(0L) / 1_000f

    val gap = if (peerClockAheadMillis != null) {
        ((from.acrossMmPerS + to.acrossMmPerS) / 2f * seconds).coerceIn(MinGapMm, MaxGapMm)
    } else {
        SlideLink.DefaultGapMm
    }
    // Where the arriving screen's top is, measured on the leaving screen: what the finger's height says, minus how
    // far it drifted up or down while crossing the gap.
    val drift = (from.alongEdgeMmPerS + to.alongEdgeMmPerS) / 2f * seconds
    val arrivingMinusLeaving = to.alongEdgeMm - from.alongEdgeMm - drift
    if (abs(arrivingMinusLeaving) > MaxOffsetMm) return null
    // The offset turns the peer's heights into this screen's: + for the arriving screen, − for the leaving one.
    val offset = if (mine.leaving) -arrivingMinusLeaving else arrivingMinusLeaving
    return SlideLink(gapMm = gap, verticalOffsetMm = offset, peerSide = mine.edge, peerMmScale = scale)
}

/**
 * How many of this phone's millimetres one of the peer's is, from one swipe's crossing speeds: the finger crosses
 * both edges at about the same speed, so if both phones reported their density right, both would measure the same.
 * 1 when the crossings are too slow to tell or disagree by more than a density error could.
 */
internal fun peerMmScale(mine: EdgeCrossing, theirs: EdgeCrossing): Float {
    if (mine.acrossMmPerS < MinCalibrationMmPerS || theirs.acrossMmPerS < MinCalibrationMmPerS) return 1f
    val scale = mine.acrossMmPerS / theirs.acrossMmPerS
    return if (abs(scale - 1f) <= MaxDensityMismatch) scale else 1f
}

/**
 * How the paired screens sit side by side: the defaults until the user lines them up, then what the line-up swipe
 * measured. Shared by the pairing flow (which measures it) and slides (which follow it).
 */
interface LinkGeometry {
    val link: StateFlow<SlideLink>

    fun update(link: SlideLink)
}
