package com.kotlistine.transferingfilebyair.feature.simulator

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer

/**
 * One twin's view of the other: messages travel over [transport], and "receiving" a file copies what the other twin
 * sent into this twin's shelf.
 */
internal class TwinPeer(
    override val transport: Transport,
    /** The edge of this twin that faces the other. */
    private val peerSide: ExitEdge,
    private val gapMm: () -> Float,
    private val storage: SimulatedStorage,
    private val shelf: InMemoryShelfRepository,
) : SlidePeer {

    override val link: SlideLink
        get() = SlideLink(gapMm = gapMm(), peerSide = peerSide)

    override suspend fun receive(file: IncomingFile): Result<String, DataError> {
        val sent = storage[file.peerItemId] ?: return Result.Failure(DataError.Local.FILE_NOT_FOUND)
        return Result.Success(shelf.add(sent))
    }
}
