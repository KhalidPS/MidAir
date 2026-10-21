package com.kotlistine.transferingfilebyair.core.transfer.data

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
    is SlideMessage.Ping -> SlideMessageDto.Ping(sentAtMillis)
    is SlideMessage.Pong -> SlideMessageDto.Pong(pingSentAtMillis)
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
    is SlideMessageDto.Ping -> SlideMessage.Ping(sentAtMillis)
    is SlideMessageDto.Pong -> SlideMessage.Pong(pingSentAtMillis)
}

private fun ExitEdge.toDto(): ExitEdgeDto = when (this) {
    ExitEdge.LEFT -> ExitEdgeDto.LEFT
    ExitEdge.RIGHT -> ExitEdgeDto.RIGHT
}

private fun ExitEdgeDto.toDomain(): ExitEdge = when (this) {
    ExitEdgeDto.LEFT -> ExitEdge.LEFT
    ExitEdgeDto.RIGHT -> ExitEdge.RIGHT
}
