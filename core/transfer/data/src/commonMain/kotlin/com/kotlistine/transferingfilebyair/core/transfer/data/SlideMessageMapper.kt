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
        atMillis = atMillis,
    )
    // Each move as it would go alone.
    is SlideMessage.DragMoves -> SlideMessageDto.DragMoves(moves.map { it.toDto() as SlideMessageDto.DragMove })
    is SlideMessage.DragCancel -> SlideMessageDto.DragCancel(itemId)
    is SlideMessage.DragCommit -> SlideMessageDto.DragCommit(itemId, outwardMmPerS, alongEdgeMmPerS)
    is SlideMessage.FileOffer -> SlideMessageDto.FileOffer(itemId, transferId)
    is SlideMessage.FileQueued -> SlideMessageDto.FileQueued(itemId)
    is SlideMessage.FileCancelled -> SlideMessageDto.FileCancelled(itemId, bySender)
    is SlideMessage.FileKept -> SlideMessageDto.FileKept(itemId)
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
    is SlideMessage.PlaybackOffer -> SlideMessageDto.PlaybackOffer(itemId, sizeBytes, positionMs, isPlaying, httpUrl)
    is SlideMessage.PlaybackHint -> SlideMessageDto.PlaybackHint(itemId, sizeBytes)
    is SlideMessage.PlaybackClock -> SlideMessageDto.PlaybackClock(itemId, positionMs, isPlaying)
    is SlideMessage.FileRoute -> SlideMessageDto.FileRoute(itemId, route.toDto())
    is SlideMessage.Grabbed -> SlideMessageDto.Grabbed(itemId, handSize)
    is SlideMessage.Released -> SlideMessageDto.Released(itemId)
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
        atMillis = atMillis,
    )
    is SlideMessageDto.DragMoves -> SlideMessage.DragMoves(moves.map { it.toDomain() as SlideMessage.DragMove })
    is SlideMessageDto.DragCancel -> SlideMessage.DragCancel(itemId)
    is SlideMessageDto.DragCommit -> SlideMessage.DragCommit(itemId, outwardMmPerS, alongEdgeMmPerS)
    is SlideMessageDto.FileOffer -> SlideMessage.FileOffer(itemId, transferId)
    is SlideMessageDto.FileQueued -> SlideMessage.FileQueued(itemId)
    is SlideMessageDto.FileCancelled -> SlideMessage.FileCancelled(itemId, bySender)
    is SlideMessageDto.FileKept -> SlideMessage.FileKept(itemId)
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
    is SlideMessageDto.PlaybackOffer -> SlideMessage.PlaybackOffer(itemId, sizeBytes, positionMs, isPlaying, httpUrl)
    is SlideMessageDto.PlaybackHint -> SlideMessage.PlaybackHint(itemId, sizeBytes)
    is SlideMessageDto.PlaybackClock -> SlideMessage.PlaybackClock(itemId, positionMs, isPlaying)
    is SlideMessageDto.FileRoute -> SlideMessage.FileRoute(itemId, route.toDomain())
    is SlideMessageDto.Grabbed -> SlideMessage.Grabbed(itemId, handSize)
    is SlideMessageDto.Released -> SlideMessage.Released(itemId)
}

private fun ExitEdge.toDto(): ExitEdgeDto = when (this) {
    ExitEdge.LEFT -> ExitEdgeDto.LEFT
    ExitEdge.RIGHT -> ExitEdgeDto.RIGHT
}

private fun ExitEdgeDto.toDomain(): ExitEdge = when (this) {
    ExitEdgeDto.LEFT -> ExitEdge.LEFT
    ExitEdgeDto.RIGHT -> ExitEdge.RIGHT
}

private fun SlideMessage.FileRoute.Route.toDto(): FileRouteDto = when (this) {
    SlideMessage.FileRoute.Route.SEND -> FileRouteDto.SEND
    SlideMessage.FileRoute.Route.FETCHING -> FileRouteDto.FETCHING
    SlideMessage.FileRoute.Route.FETCHED -> FileRouteDto.FETCHED
    SlideMessage.FileRoute.Route.CANCELLED -> FileRouteDto.CANCELLED
}

private fun FileRouteDto.toDomain(): SlideMessage.FileRoute.Route = when (this) {
    FileRouteDto.SEND -> SlideMessage.FileRoute.Route.SEND
    FileRouteDto.FETCHING -> SlideMessage.FileRoute.Route.FETCHING
    FileRouteDto.FETCHED -> SlideMessage.FileRoute.Route.FETCHED
    FileRouteDto.CANCELLED -> SlideMessage.FileRoute.Route.CANCELLED
}
