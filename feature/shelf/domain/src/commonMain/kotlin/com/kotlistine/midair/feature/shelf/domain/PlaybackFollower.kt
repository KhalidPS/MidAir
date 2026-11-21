package com.kotlistine.midair.feature.shelf.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/** Closer than this, two halves of a card show the same frame (a frame is 16–33 ms). */
private const val ToleranceMs = 25L

/** A little ahead is slowed down; further ahead, it holds its frame until the peer gets there. */
private const val SlowDownWithinMs = 300L

/**
 * Behind, it speeds up; further behind, it jumps ahead. A jump costs a frozen frame of about 2 s on the phones (it
 * loads again, 1.2 to 3.4 s measured), so
 * speed does as much as it can: the video is muted while following, so a faster pace has no pitch to give it away.
 * A second and a half behind is made up in 3 s at 1.5×, still sooner than a jump shows a moving picture.
 */
private const val SpeedUpWithinMs = 1_500L

/** At the speed limits, a gap closes this fast: 1 s behind is made up in 2 s at 1.5×. */
private const val CatchUpWindowMs = 2_000f
private const val MinSpeed = 0.85f
private const val MaxSpeed = 1.5f

/**
 * How far ahead a first load aims. It takes far longer than a jump (the file is opened, its index found): about 1.8 s
 * on a Redmi Note 8 over Wi-Fi Direct. Arriving early only shows its first frame a little longer.
 */
private const val StartLeadMs = 2_000L

/**
 * How far ahead the load aims when the card is lifted again after a pull-back: what was read of the video is still
 * here, and such a load took 0.33 to 0.43 s on the phones (ten in a row). Aimed 2 s ahead it showed its first frame
 * and then stood still on it for about 1.5 s; a load that takes longer after all is made up by playing faster.
 */
const val SecondLiftLeadMs = 600L

/**
 * How far ahead a jump aims before anything is known: jumps took about 2 s on the phones, and one aimed 1 s ahead
 * landed behind again and had to jump twice. Then it is learnt from how long jumps really take, and whatever
 * is left over is held: never more than a couple of seconds, which would show as a frozen frame.
 */
private const val InitialLeadMs = 2_000L
private const val MinLeadMs = 300L
private const val MaxLeadMs = 3_000L

/** A report this far from where the others say the sender is means it stalled or jumped: start over from it. */
private const val DiscontinuityMs = 300L

/** Only recent reports count: the sender may have stalled briefly since older ones. */
private const val WindowMs = 1_500L

/** How the receiving video changes to stay on the sender's frame. */
sealed interface PlaybackCorrection {
    /** Play at [speed] (1 = normal): a change too small to see, closing a small gap. */
    data class Speed(val speed: Float) : PlaybackCorrection

    /** Ahead: stay on this frame for [durationMs], then play on at normal speed. */
    data class Hold(val durationMs: Long) : PlaybackCorrection

    /** Too far behind to catch up: jump to [positionMs] (ahead of the peer; the rest is held). */
    data class Seek(val positionMs: Long) : PlaybackCorrection
}

/**
 * Keeps a video on this device on the same frame as the same video on the peer, from the peer's reports of where its
 * video is.
 *
 * It needs no shared clock. A report is aged by how long ago it arrived (on this device's clock) plus the one-way
 * delay of the link. Delays only ever make a report look behind, so the least delayed of the recent reports is the
 * best estimate.
 *
 * A video read from the peer takes a second or more to move again after a jump, so it never jumps to where the peer
 * is: it jumps ahead, by how long its starts take, and holds its frame for whatever is left over. Being early is
 * cheap and exact; being late would mean another jump. All times are this device's, in milliseconds.
 */
class PlaybackFollower(
    /** How far ahead of the peer's frame the first load aims: how long that load is expected to take. */
    private val startLeadMs: Long = StartLeadMs,
) {

    private class Report(val positionMs: Long, val receivedAtMs: Long)

    private val reports = ArrayDeque<Report>()
    private var oneWayMs = 0L
    private var leadMs = InitialLeadMs

    /** When the start or jump now loading was asked for; null once it plays. */
    private var loadingSinceMs: Long? = null

    /** Whether the sender's video is playing, as of the last report. */
    var isPlaying: Boolean = false
        private set

    /** The peer's video was at [positionMs] when this report left it; it arrived at [receivedAtMs]. */
    fun report(positionMs: Long, isPlaying: Boolean, receivedAtMs: Long, oneWayMs: Long) {
        this.oneWayMs = oneWayMs.coerceAtLeast(0L)
        if (isPlaying != this.isPlaying) reports.clear()
        this.isPlaying = isPlaying
        val expected = targetAt(receivedAtMs)
        val reported = positionMs + if (isPlaying) this.oneWayMs else 0L
        if (expected != null && abs(reported - expected) > DiscontinuityMs) reports.clear()
        reports.addLast(Report(positionMs, receivedAtMs))
        while (reports.first().receivedAtMs < receivedAtMs - WindowMs) reports.removeFirst()
    }

    /** Where the peer's video is at [nowMs], or null before any report. */
    fun targetAt(nowMs: Long): Long? {
        if (reports.isEmpty()) return null
        if (!isPlaying) return reports.last().positionMs
        return reports.maxOf { it.positionMs + (nowMs - it.receivedAtMs) } + oneWayMs
    }

    /**
     * Where to start this device's video, loaded at [nowMs]. A first load takes far longer than a jump (the file is
     * opened, its index found), so it teaches nothing about jumps: once it plays, the first correction catches up.
     */
    fun startAt(nowMs: Long): Long? {
        val target = targetAt(nowMs) ?: return null
        if (!isPlaying) return target
        return target + startLeadMs
    }

    /** This device's video is moving, seen at [nowMs]: if a start or jump was loading, it learns how long that took. */
    fun onPlaying(nowMs: Long) {
        val since = loadingSinceMs ?: return
        loadingSinceMs = null
        // A little more than it took: arriving early only means a short hold.
        leadMs = ((nowMs - since) * 1.25f).roundToLong().plus(150L).coerceIn(MinLeadMs, MaxLeadMs)
    }

    /** What to do now that this device's video, playing, is at [ownPositionMs]; null before any report. */
    fun correct(ownPositionMs: Long, nowMs: Long): PlaybackCorrection? {
        val target = targetAt(nowMs) ?: return null
        val error = ownPositionMs - target
        if (!isPlaying) return if (abs(error) > ToleranceMs) PlaybackCorrection.Seek(target) else null
        return when {
            abs(error) <= ToleranceMs -> PlaybackCorrection.Speed(1f)
            error > SlowDownWithinMs -> PlaybackCorrection.Hold(error)
            error > 0 -> PlaybackCorrection.Speed((1f - error / CatchUpWindowMs).coerceAtLeast(MinSpeed))
            error >= -SpeedUpWithinMs -> PlaybackCorrection.Speed((1f - error / CatchUpWindowMs).coerceAtMost(MaxSpeed))
            else -> {
                loadingSinceMs = nowMs
                PlaybackCorrection.Seek(target + leadMs)
            }
        }
    }
}
