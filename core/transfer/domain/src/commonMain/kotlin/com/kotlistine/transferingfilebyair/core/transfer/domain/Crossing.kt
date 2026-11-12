package com.kotlistine.transferingfilebyair.core.transfer.domain

/**
 * How the two screens sit relative to each other.
 *
 * @property gapMm everything between the two screens' pixels: both bezels plus the air between the devices.
 * @property verticalOffsetMm how far the sender's screen top sits below the receiver's.
 * @property peerSide the sender's edge that faces the peer, or null when unknown (any edge leads to it).
 * @property peerMmScale turns the peer's millimetres into this screen's: phones report their pixel density a little
 *   off, so the same length measures differently on each. 1 until a line-up has compared them.
 */
data class SlideLink(
    val gapMm: Float = DefaultGapMm,
    val verticalOffsetMm: Float = 0f,
    val peerSide: ExitEdge? = null,
    val peerMmScale: Float = 1f,
) {
    fun leadsToPeer(edge: ExitEdge): Boolean = peerSide == null || peerSide == edge

    /** A length or speed the peer measured, in this screen's millimetres. */
    fun fromPeer(mm: Float): Float = mm * peerMmScale

    companion object {
        /** Two phone bezels and a finger's width of air. */
        const val DefaultGapMm = 8f
    }
}

/** A card's top-left corner on the receiver's screen, in millimetres from the receiver's top-left. */
data class CardPositionMm(val leftMm: Float, val topMm: Float)

/** Streaming starts when the card's leading edge is this close to the exit edge. */
const val ApproachRangeMm = 40f

/**
 * Where the receiver draws a card the sender reports at [move], so the part that left the sender continues on the
 * receiver's facing edge as one object across the gap.
 */
fun incomingCardPosition(
    move: SlideMessage.DragMove,
    cardWidthMm: Float,
    receiverWidthMm: Float,
    link: SlideLink,
): CardPositionMm {
    // How far the leading edge is inside the receiver's screen.
    val inside = link.fromPeer(move.penetrationMm) - link.gapMm
    val left = when (move.exitEdge) {
        // Leaving the sender's right edge = entering the receiver's left edge, leading (right) side first.
        ExitEdge.RIGHT -> inside - link.fromPeer(cardWidthMm)
        ExitEdge.LEFT -> receiverWidthMm - inside
    }
    return CardPositionMm(leftMm = left, topMm = link.fromPeer(move.alongEdgeMm) + link.verticalOffsetMm)
}

/** How fast the card at [move] moves across the receiver's screen, in its millimetres per second (x right, y down). */
fun incomingCardVelocity(move: SlideMessage.DragMove, link: SlideLink): CardPositionMm = CardPositionMm(
    // Outward from the sender's right edge is rightward here; from its left edge, leftward.
    leftMm = link.fromPeer(if (move.exitEdge == ExitEdge.RIGHT) move.outwardMmPerS else -move.outwardMmPerS),
    topMm = link.fromPeer(move.alongEdgeMmPerS),
)

/**
 * How near the card is to arriving: 0 while it is [ApproachRangeMm] or more from the receiver's edge, easing to 1 as
 * its leading edge reaches it. The receiver's bloom and blur ramp with it, so it visibly anticipates the card.
 */
fun SlideMessage.DragMove.arrival(link: SlideLink): Float {
    val t = ((link.fromPeer(penetrationMm) - link.gapMm + ApproachRangeMm) / ApproachRangeMm).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** This move extrapolated [seconds] ahead at its own velocity: hides link latency on the receiver. */
fun SlideMessage.DragMove.predictedAhead(seconds: Float): SlideMessage.DragMove = copy(
    alongEdgeMm = alongEdgeMm + alongEdgeMmPerS * seconds,
    penetrationMm = penetrationMm + outwardMmPerS * seconds,
)
