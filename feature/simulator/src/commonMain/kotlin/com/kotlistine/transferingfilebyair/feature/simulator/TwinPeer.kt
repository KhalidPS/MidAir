package com.kotlistine.transferingfilebyair.feature.simulator

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferProgress
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /** The twins are always linked. */
    override val isLinked: Flow<Boolean> = flowOf(true)

    /** Both twins share the simulator's storage, so there are no bytes to move: the other twin copies on receive. */
    override suspend fun send(file: OutgoingFile, progress: TransferProgress): EmptyResult<DataError> = Result.Success(Unit)

    override suspend fun receive(file: IncomingFile, progress: TransferProgress): Result<String, DataError> {
        val sent = storage[file.peerItemId] ?: return Result.Failure(DataError.Local.FILE_NOT_FOUND)
        progress.progressed(file.sizeBytes)
        return Result.Success(shelf.add(sent))
    }

    /** Twins copy instantly; there is nothing in flight to stop. */
    override fun cancelSend(itemId: String) = Unit

    override fun cancelReceive(peerItemId: String) = Unit
}
