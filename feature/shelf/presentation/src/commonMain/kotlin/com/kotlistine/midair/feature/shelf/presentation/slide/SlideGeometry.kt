package com.kotlistine.midair.feature.shelf.presentation.slide

import androidx.compose.ui.geometry.Rect
import com.kotlistine.midair.core.transfer.domain.ExitEdge

sealed interface ReleaseDecision {
    data class Send(val edge: ExitEdge) : ReleaseDecision
    data object Return : ReleaseDecision
}

/** How strongly an edge draws the card toward it: 0 = not at all, 1 = far enough past the edge to send. */
data class EdgePull(val edge: ExitEdge, val strength: Float)

/**
 * One frame of a carried card on a screen [screenWidthPx] wide: where it is and how fast it moves (pixels, pixels per
 * second). Which edge it is measured against is up to whoever mirrors it, so the edge never flips under them.
 */
data class SlideFrame(
    val id: String,
    val cardLeftPx: Float,
    val cardTopPx: Float,
    val cardRightPx: Float,
    val screenWidthPx: Float,
    val velocityXPxPerS: Float,
    val velocityYPxPerS: Float,
    val tiltDegrees: Float,
    /** When this frame was drawn, on the frame clock; 0 when unknown. */
    val frameTimeNanos: Long = 0L,
) {
    /** The edge the card is closer to (by its centre). */
    val nearestEdge: ExitEdge
        get() = if ((cardLeftPx + cardRightPx) / 2f >= screenWidthPx / 2f) ExitEdge.RIGHT else ExitEdge.LEFT

    /** How far the card's leading side is past [edge]; negative while it is inside. */
    fun penetrationPx(edge: ExitEdge): Float = when (edge) {
        ExitEdge.LEFT -> -cardLeftPx
        ExitEdge.RIGHT -> cardRightPx - screenWidthPx
    }

    /** Speed toward [edge]; negative when moving back inward. */
    fun outwardPxPerS(edge: ExitEdge): Float = if (edge == ExitEdge.RIGHT) velocityXPxPerS else -velocityXPxPerS
}

/** Fraction of the card's width that must be past an edge when it is let go for it to be sent. */
internal const val SendThreshold = 0.35f

/** A release is judged where the card would be this long later at its release speed, so a flick toward an edge sends. */
internal const val FlingProjectionSeconds = 0.12f

/** The pull never starts further than this (in card widths) from the edge, so a card across the screen pulls nothing. */
private const val MaxPullDistance = 0.6f

/** Fraction of [card]'s width that lies past [edge] of a container [containerWidth] wide; negative while inside. */
internal fun overflow(card: Rect, containerWidth: Float, edge: ExitEdge): Float = when (edge) {
    ExitEdge.LEFT -> -card.left / card.width
    ExitEdge.RIGHT -> (card.right - containerWidth) / card.width
}

/** Whether a card let go at [card] while moving at [velocityX] px/s is sent through an edge or springs back. */
internal fun decideRelease(card: Rect, containerWidth: Float, velocityX: Float): ReleaseDecision {
    val projected = card.translate(velocityX * FlingProjectionSeconds, 0f)
    val edge = ExitEdge.entries.maxBy { overflow(projected, containerWidth, it) }
    return if (overflow(projected, containerWidth, edge) >= SendThreshold) ReleaseDecision.Send(edge) else ReleaseDecision.Return
}

/**
 * The pull of the edge nearest to [card]. Shelf cards rest close to an edge, so the pull is measured from where the
 * card was lifted ([rest]): it is 0 there and eases up to 1 at the send threshold.
 */
internal fun edgePull(card: Rect, rest: Rect, containerWidth: Float): EdgePull {
    val edge = if (card.center.x >= containerWidth / 2) ExitEdge.RIGHT else ExitEdge.LEFT
    val start = overflow(rest, containerWidth, edge).coerceIn(-MaxPullDistance, SendThreshold - 0.05f)
    val t = ((overflow(card, containerWidth, edge) - start) / (SendThreshold - start)).coerceIn(0f, 1f)
    return EdgePull(edge, strength = t * t * (3f - 2f * t))
}
