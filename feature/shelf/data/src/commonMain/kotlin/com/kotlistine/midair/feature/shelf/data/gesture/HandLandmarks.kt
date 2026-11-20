package com.kotlistine.midair.feature.shelf.data.gesture

import com.kotlistine.midair.feature.shelf.domain.gesture.HandFrame
import com.kotlistine.midair.feature.shelf.domain.gesture.HandPose
import kotlin.math.hypot

/** One of the hand's points in a picture, in pixels. */
data class HandPoint(val x: Float, val y: Float)

/** How many points MediaPipe's hand model finds: the wrist, then four per finger from the thumb to the pinky. */
const val HandPointCount = 21

private const val Wrist = 0

/** Each finger but the thumb, as (the knuckle it grows from, its middle joint, its tip). */
private val Fingers = listOf(
    Triple(5, 6, 8),
    Triple(9, 10, 12),
    Triple(13, 14, 16),
    Triple(17, 18, 20),
)

/** A fingertip this much farther from the wrist than its middle joint is straightened out. */
private const val StretchedRatio = 1.15f

/** A fingertip nearer the wrist than its middle joint is curled into the palm. */
private const val CurledRatio = 1f

/**
 * What a hand found in a [width] × [height] picture is doing, from its [HandPointCount] [points]: open when all four
 * fingers are stretched out, a fist when none is and at least three are curled into the palm. The thumb is left
 * out: it sits close to the palm in an open hand too. Points are as seen in the picture, which the caller has
 * already mirrored.
 */
fun handFrameOf(points: List<HandPoint>, width: Int, height: Int, atMillis: Long): HandFrame {
    if (points.size < HandPointCount || width <= 0 || height <= 0) return HandFrame(HandPose.NONE, atMillis = atMillis)
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    return HandFrame(
        pose = poseOf(points),
        x = ((minX + maxX) / 2f / width).coerceIn(0f, 1f),
        y = ((minY + maxY) / 2f / height).coerceIn(0f, 1f),
        size = maxOf((maxX - minX) / width, (maxY - minY) / height).coerceIn(0f, 1f),
        atMillis = atMillis,
    )
}

private fun poseOf(points: List<HandPoint>): HandPose {
    val wrist = points[Wrist]
    var stretched = 0
    var curled = 0
    for ((_, joint, tip) in Fingers) {
        val toTip = distance(wrist, points[tip])
        val toJoint = distance(wrist, points[joint])
        when {
            toTip > toJoint * StretchedRatio -> stretched++
            toTip < toJoint * CurledRatio -> curled++
        }
    }
    return when {
        stretched == Fingers.size -> HandPose.OPEN
        stretched == 0 && curled >= Fingers.size - 1 -> HandPose.FIST
        else -> HandPose.OTHER
    }
}

private fun distance(a: HandPoint, b: HandPoint): Float = hypot(a.x - b.x, a.y - b.y)
