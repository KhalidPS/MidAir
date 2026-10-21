package com.kotlistine.transferingfilebyair.feature.pairing.presentation.lineup

import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge

/** Velocity is measured over this much of the swipe next to the edge: long enough to smooth out touch noise. */
private const val VelocityWindowMillis = 60L

/** A touch point: where on the screen (pixels), and when by the touch events' own clock. */
internal data class TouchSample(val x: Float, val y: Float, val uptimeMillis: Long)

/**
 * Where a swipe crossed this screen's left or right edge, in pixels.
 *
 * @property millisFromLast when the finger was on the edge line, relative to the swipe's last touch: negative for a
 * swipe that came in (it crossed before it was first seen), positive for one that left (it crossed after the screen
 * last felt it, on the bezel).
 */
data class SwipeCrossingPx(
    val leaving: Boolean,
    val edge: ExitEdge,
    val alongEdgePx: Float,
    val millisFromLast: Long,
    val acrossPxPerS: Float,
    val alongEdgePxPerS: Float,
)

/**
 * Whether a finished swipe ([samples], in order) came in through an edge or went out through one: it must start (or
 * end) within [edgeZonePx] of the edge, moving through it at [minSpeedPxPerS] or more. The crossing point and time
 * are carried on to the edge line at the swipe's speed. A swipe that came in counts as arriving even if it also
 * left again.
 */
internal fun edgeCrossing(
    samples: List<TouchSample>,
    widthPx: Float,
    edgeZonePx: Float,
    minSpeedPxPerS: Float,
): SwipeCrossingPx? {
    if (samples.size < 2 || widthPx <= 0f) return null
    val first = samples.first()
    val last = samples.last()

    val entryEdge = edgeNear(first.x, widthPx, edgeZonePx)
    if (entryEdge != null) {
        val probe = samples.firstOrNull { it.uptimeMillis - first.uptimeMillis >= VelocityWindowMillis } ?: last
        val seconds = (probe.uptimeMillis - first.uptimeMillis) / 1_000f
        if (seconds > 0f) {
            val vx = (probe.x - first.x) / seconds
            val vy = (probe.y - first.y) / seconds
            val inward = if (entryEdge == ExitEdge.LEFT) vx else -vx
            if (inward >= minSpeedPxPerS) {
                val fromEdge = if (entryEdge == ExitEdge.LEFT) first.x else widthPx - first.x
                val before = fromEdge / inward
                return SwipeCrossingPx(
                    leaving = false,
                    edge = entryEdge,
                    alongEdgePx = first.y - vy * before,
                    millisFromLast = first.uptimeMillis - last.uptimeMillis - (before * 1_000f).toLong(),
                    acrossPxPerS = inward,
                    alongEdgePxPerS = vy,
                )
            }
        }
    }

    val exitEdge = edgeNear(last.x, widthPx, edgeZonePx) ?: return null
    val probe = samples.lastOrNull { last.uptimeMillis - it.uptimeMillis >= VelocityWindowMillis } ?: first
    val seconds = (last.uptimeMillis - probe.uptimeMillis) / 1_000f
    if (seconds <= 0f) return null
    val vx = (last.x - probe.x) / seconds
    val vy = (last.y - probe.y) / seconds
    val outward = if (exitEdge == ExitEdge.RIGHT) vx else -vx
    if (outward < minSpeedPxPerS) return null
    val toEdge = if (exitEdge == ExitEdge.RIGHT) widthPx - last.x else last.x
    val after = toEdge / outward
    return SwipeCrossingPx(
        leaving = true,
        edge = exitEdge,
        alongEdgePx = last.y + vy * after,
        millisFromLast = (after * 1_000f).toLong(),
        acrossPxPerS = outward,
        alongEdgePxPerS = vy,
    )
}

private fun edgeNear(x: Float, widthPx: Float, zonePx: Float): ExitEdge? = when {
    x <= zonePx -> ExitEdge.LEFT
    x >= widthPx - zonePx -> ExitEdge.RIGHT
    else -> null
}
