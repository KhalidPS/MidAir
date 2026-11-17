package com.kotlistine.transferingfilebyair.feature.shelf.data.gesture

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A palm box this many times bigger, around the fingers too, is what the hand model is shown. */
private const val PalmToHand = 2.6f

/** The hand stretches past the palm toward the fingers: the square moves this much of the palm's size that way. */
private const val PalmShift = 0.5f

/** The square around last picture's hand points, this many times bigger, keeps the hand in it as it moves. */
private const val PointsToHand = 2f

/** Like [PalmShift], for the square made from the hand's own points. */
private const val PointsShift = 0.1f

/** The wrist and the middle finger's base among the hand model's 21 points: the line between them is "up" for the hand. */
private const val Wrist = 0
private const val MiddleBase = 9

/**
 * A square of the picture around a hand, turned by [angle] (radians, clockwise from up) so that the hand points up
 * in it: what the hand model is shown, and how its answer maps back to the picture.
 */
data class HandRegion(val centreX: Float, val centreY: Float, val side: Float, val angle: Float) {

    /**
     * The affine transform from picture pixels to a [size]-pixel model input, row by row (a, b, c, d, e, f: input
     * x = a·x + b·y + c, input y = d·x + e·y + f).
     */
    fun toInput(size: Int): FloatArray {
        val s = size / side
        val c = cos(angle)
        val n = sin(angle)
        val half = size / 2f
        return floatArrayOf(
            s * c, s * n, half - s * (c * centreX + n * centreY),
            -s * n, s * c, half - s * (-n * centreX + c * centreY),
        )
    }

    /** A point of a [size]-pixel model input, back in picture pixels. */
    fun toPicture(inputX: Float, inputY: Float, size: Int): HandPoint {
        val s = size / side
        val u = (inputX - size / 2f) / s
        val v = (inputY - size / 2f) / s
        val c = cos(angle)
        val n = sin(angle)
        return HandPoint(centreX + c * u - n * v, centreY + n * u + c * v)
    }

    companion object {
        /** The square the whole hand is in, from the palm the detector found. */
        fun fromPalm(palm: Palm): HandRegion {
            val angle = angleOf(palm.points[0], palm.points[2])
            val palmSize = maxOf(palm.right - palm.left, palm.bottom - palm.top)
            val shift = PalmShift * palmSize
            return HandRegion(
                centreX = (palm.left + palm.right) / 2f + sin(angle) * shift,
                centreY = (palm.top + palm.bottom) / 2f - cos(angle) * shift,
                side = PalmToHand * palmSize,
                angle = angle,
            )
        }

        /** The square to look in next picture, from where the hand's [points] are in this one. */
        fun fromPoints(points: List<HandPoint>): HandRegion {
            val angle = angleOf(points[Wrist], points[MiddleBase])
            val c = cos(angle)
            val n = sin(angle)
            // The points as the hand sees them (turned upright), to box them along the hand.
            val along = points.map { HandPoint(c * it.x + n * it.y, -n * it.x + c * it.y) }
            val minX = along.minOf { it.x }
            val maxX = along.maxOf { it.x }
            val minY = along.minOf { it.y }
            val maxY = along.maxOf { it.y }
            val size = maxOf(maxX - minX, maxY - minY)
            val uprightX = (minX + maxX) / 2f
            val uprightY = (minY + maxY) / 2f - PointsShift * size
            return HandRegion(
                centreX = c * uprightX - n * uprightY,
                centreY = n * uprightX + c * uprightY,
                side = PointsToHand * size,
                angle = angle,
            )
        }

        /** How far the line from [wrist] to [knuckle] is turned clockwise from straight up. */
        private fun angleOf(wrist: HandPoint, knuckle: HandPoint): Float =
            atan2(knuckle.x - wrist.x, -(knuckle.y - wrist.y))
    }
}
