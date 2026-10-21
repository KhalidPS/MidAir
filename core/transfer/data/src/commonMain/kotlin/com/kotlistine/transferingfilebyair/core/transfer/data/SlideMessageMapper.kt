package com.kotlistine.transferingfilebyair.core.transfer.data

import com.kotlistine.transferingfilebyair.core.transfer.domain.EdgeCrossing
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage

internal fun SlideMessage.toDto(): SlideMessageDto = when (this) {
    is SlideMessage.DragStart -> SlideMessageDto.DragStart(
        itemId = itemId,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        cardWidthMm = cardWidthMm,
        cardHeightMm = cardHeightMm,
        thumbnail = thumbnail,
    )
    is SlideMessage.DragMove -> SlideMessageDto.DragMove(
        itemId = itemId,
        seq = seq,
        exitEdge = exitEdge.toDto(),
        alongEdgeMm = alongEdgeMm,
        penetrationMm = penetrationMm,
        outwardMmPerS = outwardMmPerS,
        alongEdgeMmPerS = alongEdgeMmPerS,
        tiltDegrees = tiltDegrees,
    )
    is SlideMessage.DragCancel -> SlideMessageDto.DragCancel(itemId)
    is SlideMessage.DragCommit -> SlideMessageDto.DragCommit(itemId, outwardMmPerS, alongEdgeMmPerS)
    is SlideMessage.FileOffer -> SlideMessageDto.FileOffer(itemId, transferId)
    is SlideMessage.Ping -> SlideMessageDto.Ping(sentAtMillis)
    is SlideMessage.Pong -> SlideMessageDto.Pong(pingSentAtMillis, answeredAtMillis)
    is SlideMessage.SwipeCrossed -> SlideMessageDto.SwipeCrossed(
        leaving = crossing.leaving,
        edge = crossing.edge.toDto(),
        alongEdgeMm = crossing.alongEdgeMm,
        atMillis = crossing.atMillis,
        acrossMmPerS = crossing.acrossMmPerS,
        alongEdgeMmPerS = crossing.alongEdgeMmPerS,
    )
}

internal fun SlideMessageDto.toDomain(): SlideMessage = when (this) {
    is SlideMessageDto.DragStart -> SlideMessage.DragStart(
        itemId = itemId,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        cardWidthMm = cardWidthMm,
        cardHeightMm = cardHeightMm,
        thumbnail = thumbnail,
    )
    is SlideMessageDto.DragMove -> SlideMessage.DragMove(
        itemId = itemId,
        seq = seq,
        exitEdge = exitEdge.toDomain(),
        alongEdgeMm = alongEdgeMm,
        penetrationMm = penetrationMm,
        outwardMmPerS = outwardMmPerS,
        alongEdgeMmPerS = alongEdgeMmPerS,
        tiltDegrees = tiltDegrees,
    )
    is SlideMessageDto.DragCancel -> SlideMessage.DragCancel(itemId)
    is SlideMessageDto.DragCommit -> SlideMessage.DragCommit(itemId, outwardMmPerS, alongEdgeMmPerS)
    is SlideMessageDto.FileOffer -> SlideMessage.FileOffer(itemId, transferId)
    is SlideMessageDto.Ping -> SlideMessage.Ping(sentAtMillis)
    is SlideMessageDto.Pong -> SlideMessage.Pong(pingSentAtMillis, answeredAtMillis)
    is SlideMessageDto.SwipeCrossed -> SlideMessage.SwipeCrossed(
        EdgeCrossing(
            leaving = leaving,
            edge = edge.toDomain(),
            alongEdgeMm = alongEdgeMm,
            atMillis = atMillis,
            acrossMmPerS = acrossMmPerS,
            alongEdgeMmPerS = alongEdgeMmPerS,
        ),
    )
}

private fun ExitEdge.toDto(): ExitEdgeDto = when (this) {
    ExitEdge.LEFT -> ExitEdgeDto.LEFT
    ExitEdge.RIGHT -> ExitEdgeDto.RIGHT
}

private fun ExitEdgeDto.toDomain(): ExitEdge = when (this) {
    ExitEdgeDto.LEFT -> ExitEdge.LEFT
    ExitEdgeDto.RIGHT -> ExitEdge.RIGHT
}
