package com.kotlistine.midair.feature.shelf.domain.gesture

/** A whole hand gesture, at the hand's position and size in the picture (0..1, mirrored, like [HandFrame]). */
sealed interface AirGesture {
    val x: Float
    val y: Float
    val size: Float

    /** An open hand closed into a fist: take what is on the screen. */
    data class Grab(override val x: Float, override val y: Float, override val size: Float) : AirGesture

    /** A fist opened into an open hand: let go of what the hand holds. */
    data class Release(override val x: Float, override val y: Float, override val size: Float) : AirGesture

    /**
     * An open hand came into view, without a fist seen just before it. A hand carrying a card often closes out of
     * this camera's sight and comes in already opening, so while a card waits to be caught (or is held) this counts
     * like a [Release]. Before a grab it means nothing: every grab starts with one.
     */
    data class Open(override val x: Float, override val y: Float, override val size: Float) : AirGesture
}

/**
 * Turns hand frames into [AirGesture]s. A pose counts once it has been seen steadily for [holdMillis], so a single
 * misread picture never triggers anything; one stray picture in the middle of a pose doesn't restart that wait
 * either. A grab is a steady fist that follows a steady open hand within [memoryMillis]; a release is the other way
 * round; any other steady open hand is an [AirGesture.Open]. A hand smaller than [minHandSize] (far from the phone)
 * is ignored as if it weren't there, so people walking by don't grab anything.
 *
 * The open hand before a fist is remembered across short gaps without a hand: the pose model often misses the hand
 * for a few pictures while it closes. After a release (or [requireHandAway]) the hand has to leave the camera's view
 * before it counts again, so a hand that relaxes back into a fist after letting go doesn't take the next card.
 */
class AirGestureDetector(
    private val holdMillis: Long = 200L,
    private val memoryMillis: Long = 1_500L,
    private val minHandSize: Float = 0.18f,
) {
    private var candidate = HandPose.NONE
    private var candidateSince = 0L

    /** This steady pose was reported already: one gesture per pose. */
    private var isReported = false

    /** A picture that disagreed with [candidate]; a second one in a row switches to it. */
    private var stray: HandPose? = null
    private var strayAt = 0L

    private var lastSteadyOpenAt: Long? = null
    private var lastSteadyFistAt: Long? = null
    private var needsHandAway = false

    /** Feeds one frame; returns the gesture it completes, if any. Frames must come in time order. */
    fun onFrame(frame: HandFrame): AirGesture? {
        val pose = if (frame.pose != HandPose.NONE && frame.size < minHandSize) HandPose.NONE else frame.pose
        if (pose == candidate) {
            stray = null
        } else if (pose != stray) {
            // One picture alone could be a misread: wait for the next before believing it.
            stray = pose
            strayAt = frame.atMillis
            return null
        } else {
            // Seen twice in a row: it is the new pose, since the first of the two.
            candidate = pose
            candidateSince = strayAt
            isReported = false
            stray = null
        }
        val now = frame.atMillis
        if (now - candidateSince < holdMillis) return null
        val isFirst = !isReported
        isReported = true
        return when (pose) {
            // A hand that just let go and closes again is only relaxing: neither a grab, nor a fist to release.
            HandPose.FIST -> if (needsHandAway) null else {
                val openedAt = lastSteadyOpenAt
                lastSteadyFistAt = now
                if (openedAt != null && now - openedAt <= memoryMillis + holdMillis) {
                    // Used up: the same open hand can't start a second grab.
                    lastSteadyOpenAt = null
                    AirGesture.Grab(frame.x, frame.y, frame.size)
                } else {
                    null
                }
            }
            HandPose.OPEN -> if (needsHandAway) null else {
                val clenchedAt = lastSteadyFistAt
                lastSteadyOpenAt = now
                when {
                    clenchedAt != null && now - clenchedAt <= memoryMillis + holdMillis -> {
                        lastSteadyFistAt = null
                        needsHandAway = true
                        AirGesture.Release(frame.x, frame.y, frame.size)
                    }
                    isFirst -> AirGesture.Open(frame.x, frame.y, frame.size)
                    else -> null
                }
            }
            HandPose.NONE -> {
                needsHandAway = false
                null
            }
            HandPose.OTHER -> null
        }
    }

    /** The hand just let go of something: it must leave the view before it grabs or lets go again. */
    fun requireHandAway() {
        needsHandAway = true
        lastSteadyOpenAt = null
        lastSteadyFistAt = null
    }

    /** Forgets everything seen so far, as if the camera had just been turned on. */
    fun reset() {
        candidate = HandPose.NONE
        candidateSince = 0L
        isReported = false
        stray = null
        strayAt = 0L
        lastSteadyOpenAt = null
        lastSteadyFistAt = null
        needsHandAway = false
    }
}
