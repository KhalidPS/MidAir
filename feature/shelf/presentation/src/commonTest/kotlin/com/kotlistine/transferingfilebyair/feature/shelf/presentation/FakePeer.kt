package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf

class FakeTransport : Transport {
    val sent = mutableListOf<SlideMessage>()

    /** Sends for which this returns an error fail with it (and are not recorded). */
    var failWith: (SlideMessage) -> DataError.Network? = { null }
    private val inbound = MutableSharedFlow<SlideMessage>()

    override val incoming: Flow<SlideMessage> = inbound

    /** When set, every send waits for it first, as on a link busy with a file. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
        gate?.await()
        failWith(message)?.let { return Result.Failure(it) }
        sent += message
        return Result.Success(Unit)
    }

    /** What was sent, minus the round-trip probes that run in the background and the hints of what plays here. */
    val sentSlides: List<SlideMessage>
        get() = sent.filterNot { it is SlideMessage.Ping || it is SlideMessage.Pong || it is SlideMessage.PlaybackHint }

    /** Each video said to be playing here, for the peer to read its index ahead. */
    val sentHints: List<SlideMessage.PlaybackHint> get() = sent.filterIsInstance<SlideMessage.PlaybackHint>()

    suspend fun deliver(message: SlideMessage) = inbound.emit(message)
}

/** A peer to the right of this screen, 8 mm away. */
class FakePeer(
    override val link: SlideLink = SlideLink(gapMm = 8f, peerSide = ExitEdge.RIGHT),
    private val onReceive: suspend (IncomingFile) -> Result<String, DataError> = { Result.Success("received-1") },
    /** Progress reported while receiving (bytes so far), before the result. */
    private val progressSteps: List<Long> = emptyList(),
    override val media: MediaChannel? = null,
) : SlidePeer {
    override val transport = FakeTransport()
    val received = mutableListOf<IncomingFile>()
    val sentFiles = mutableListOf<OutgoingFile>()

    /** What sending a file returns. */
    var sendResult: EmptyResult<DataError> = Result.Success(Unit)

    override val isLinked: Flow<Boolean> = flowOf(true)

    /** When set, a send waits for this before returning [sendResult]. */
    var sendGate: CompletableDeferred<Unit>? = null

    override suspend fun send(file: OutgoingFile, progress: TransferProgress): EmptyResult<DataError> {
        sentFiles += file
        sendGate?.await()
        return sendResult
    }

    val cancelledSends = mutableListOf<String>()

    override fun cancelSend(itemId: String) {
        cancelledSends += itemId
    }

    val cancelledReceives = mutableListOf<String>()

    override suspend fun receive(file: IncomingFile, progress: TransferProgress): Result<String, DataError> {
        received += file
        progressSteps.forEach(progress::progressed)
        return onReceive(file)
    }

    override fun cancelReceive(peerItemId: String) {
        cancelledReceives += peerItemId
    }
}
