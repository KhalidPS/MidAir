package com.kotlistine.transferingfilebyair.core.transfer.data

import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import kotlin.math.abs

/** No phone or laptop screen is a metre across: anything past this is not a real position or card size. */
private const val MaxDistanceMm = 1_000f

/** Far beyond any flick a hand can make. */
private const val MaxSpeedMmPerS = 100_000f
private const val MaxNameLength = 1_024
private const val MaxUrlLength = 1_024

/**
 * Whether a decoded message describes something physically possible. Sizes and positions reach layout and drawing
 * on the receiver, so NaN, negative or absurd values from a faulty or hostile peer must stop here.
 */
internal fun SlideMessage.isPlausible(): Boolean = when (this) {
    is SlideMessage.DragStart ->
        name.length <= MaxNameLength &&
            sizeBytes >= 0 &&
            cardWidthMm.isSize() &&
            cardHeightMm.isSize()
    is SlideMessage.DragMove ->
        seq >= 0 &&
            alongEdgeMm.isDistance() &&
            penetrationMm.isDistance() &&
            outwardMmPerS.isSpeed() &&
            alongEdgeMmPerS.isSpeed() &&
            tiltDegrees.isFinite() &&
            abs(tiltDegrees) <= 90f &&
            atMillis >= 0L
    is SlideMessage.DragCommit -> outwardMmPerS.isSpeed() && alongEdgeMmPerS.isSpeed()
    is SlideMessage.SwipeCrossed ->
        crossing.alongEdgeMm.isDistance() &&
            crossing.acrossMmPerS.isSpeed() &&
            crossing.acrossMmPerS >= 0f &&
            crossing.alongEdgeMmPerS.isSpeed()
    is SlideMessage.PlaybackOffer ->
        sizeBytes > 0 &&
            positionMs >= 0 &&
            httpUrl.let { it == null || (it.startsWith("http://") && it.length <= MaxUrlLength) }
    is SlideMessage.PlaybackClock -> positionMs >= 0
    is SlideMessage.Grabbed -> handSize.isFinite() && handSize in 0f..1f
    is SlideMessage.DragCancel, is SlideMessage.FileOffer, is SlideMessage.Ping, is SlideMessage.Pong,
    is SlideMessage.FileRoute, is SlideMessage.Released, is SlideMessage.FileQueued, is SlideMessage.FileCancelled,
    -> true
}

private fun Float.isSize() = isFinite() && this > 0f && this <= MaxDistanceMm

private fun Float.isDistance() = isFinite() && abs(this) <= MaxDistanceMm

private fun Float.isSpeed() = isFinite() && abs(this) <= MaxSpeedMmPerS
