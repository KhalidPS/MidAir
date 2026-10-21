package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf

class FakeTransport : Transport {
    val sent = mutableListOf<SlideMessage>()

    /** Sends for which this returns an error fail with it (and are not recorded). */
    var failWith: (SlideMessage) -> DataError.Network? = { null }
    private val inbound = MutableSharedFlow<SlideMessage>()

    override val incoming: Flow<SlideMessage> = inbound

    override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
        failWith(message)?.let { return Result.Failure(it) }
        sent += message
        return Result.Success(Unit)
    }

    /** What was sent, minus the round-trip probes that run in the background. */
    val sentSlides: List<SlideMessage>
        get() = sent.filterNot { it is SlideMessage.Ping || it is SlideMessage.Pong }

    suspend fun deliver(message: SlideMessage) = inbound.emit(message)
}

/** A peer to the right of this screen, 8 mm away. */
class FakePeer(
    override val link: SlideLink = SlideLink(gapMm = 8f, peerSide = ExitEdge.RIGHT),
    private val onReceive: (IncomingFile) -> Result<String, DataError> = { Result.Success("received-1") },
) : SlidePeer {
    override val transport = FakeTransport()
    val received = mutableListOf<IncomingFile>()
    val sentFiles = mutableListOf<OutgoingFile>()

    /** What sending a file returns. */
    var sendResult: EmptyResult<DataError> = Result.Success(Unit)

    override val isLinked: Flow<Boolean> = flowOf(true)

    override suspend fun send(file: OutgoingFile): EmptyResult<DataError> {
        sentFiles += file
        return sendResult
    }

    override suspend fun receive(file: IncomingFile): Result<String, DataError> {
        received += file
        return onReceive(file)
    }
}
