package com.kotlistine.transferingfilebyair.feature.shelf.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.single
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferBoard
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferDirection
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKey
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferProgress
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferStatus
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnattendedReceiverTest {

    private val peer = FakePeer()
    private val board = TransferBoard()
    private val keepAlive = RecordingKeepAlive()

    private fun TestScope.receiver(): Pair<UnattendedReceiver, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return UnattendedReceiver(peer, board, keepAlive, scope) to scope
    }

    @Test
    fun `with no screen open, a let-go card's file is received and listed`() = runTest {
        val (_, scope) = receiver()

        peer.deliver(start("9"))
        peer.deliver(commit("9"))

        assertThat(peer.received).containsExactly(
            IncomingFile("9", "River Valley.jpg", "image/jpeg", 2048L, Delivery.SENT),
        )
        assertThat(board.transfers.value).single().transform { it.key to it.status }
            .isEqualTo(TransferKey(TransferDirection.RECEIVING, "9") to TransferStatus.DONE)
        scope.cancel()
    }

    @Test
    fun `the app is kept alive while the file comes in`() = runTest {
        val arrival = CompletableDeferred<Result<String, DataError>>()
        peer.onReceive = { arrival.await() }
        val (_, scope) = receiver()

        peer.deliver(start("9"))
        peer.deliver(commit("9"))

        assertThat(keepAlive.held).isEqualTo(1)
        assertThat(keepAlive.descriptions).containsExactly("Receiving “River Valley.jpg”")
        arrival.complete(Result.Success("new-1"))
        assertThat(keepAlive.held).isEqualTo(0)
        scope.cancel()
    }

    @Test
    fun `while a screen is open, the peer's cards are left to it`() = runTest {
        val (receiver, scope) = receiver()
        receiver.attend()

        peer.deliver(start("9"))
        peer.deliver(commit("9"))

        assertThat(peer.received).isEmpty()
        scope.cancel()
    }

    @Test
    fun `once the screen is gone, a card it saw start is received here`() = runTest {
        val (receiver, scope) = receiver()
        val release = receiver.attend()
        peer.deliver(start("9"))

        release()
        peer.deliver(commit("9"))

        assertThat(peer.received.map { it.peerItemId }).containsExactly("9")
        scope.cancel()
    }

    @Test
    fun `a release called twice counts once`() = runTest {
        val (receiver, scope) = receiver()
        receiver.attend()
        val release = receiver.attend()

        release()
        release()
        peer.deliver(start("9"))
        peer.deliver(commit("9"))

        assertThat(peer.received).isEmpty()
        scope.cancel()
    }

    @Test
    fun `a card withdrawn before its let-go is not received`() = runTest {
        val (_, scope) = receiver()

        peer.deliver(start("9"))
        peer.deliver(SlideMessage.DragCancel("9"))
        peer.deliver(commit("9"))

        assertThat(peer.received).isEmpty()
        scope.cancel()
    }

    @Test
    fun `a card that offered its video asks for the file to be sent`() = runTest {
        val (_, scope) = receiver()

        peer.deliver(start("9"))
        peer.deliver(SlideMessage.PlaybackOffer("9", sizeBytes = 2048L, positionMs = 0L, isPlaying = true))
        peer.deliver(commit("9"))

        assertThat(peer.received.map { it.delivery }).containsExactly(Delivery.ASK_TO_SEND)
        scope.cancel()
    }

    @Test
    fun `a file that fails is listed as failed`() = runTest {
        peer.onReceive = { Result.Failure(DataError.Local.DISK_FULL) }
        val (_, scope) = receiver()

        peer.deliver(start("9"))
        peer.deliver(commit("9"))

        assertThat(board.transfers.value).single().transform { it.status }.isEqualTo(TransferStatus.FAILED)
        scope.cancel()
    }

    private fun start(id: String) = SlideMessage.DragStart(
        itemId = id,
        name = "River Valley.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048L,
        cardWidthMm = 40f,
        cardHeightMm = 30f,
        thumbnail = null,
    )

    private fun commit(id: String) = SlideMessage.DragCommit(id, outwardMmPerS = 200f, alongEdgeMmPerS = 0f)

    private class FakePeer : SlidePeer {
        private val inbound = MutableSharedFlow<SlideMessage>()
        val received = mutableListOf<IncomingFile>()
        var onReceive: suspend (IncomingFile) -> Result<String, DataError> = { Result.Success("new-1") }

        override val transport = object : Transport {
            override val incoming: Flow<SlideMessage> = inbound
            override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> = Result.Success(Unit)
        }
        override val link = SlideLink(gapMm = 8f, peerSide = ExitEdge.RIGHT)
        override val isLinked: Flow<Boolean> = flowOf(true)

        suspend fun deliver(message: SlideMessage) = inbound.emit(message)

        override suspend fun send(file: OutgoingFile, progress: TransferProgress): EmptyResult<DataError> =
            Result.Success(Unit)

        override fun cancelSend(itemId: String) = Unit

        override suspend fun receive(file: IncomingFile, progress: TransferProgress): Result<String, DataError> {
            received += file
            return onReceive(file)
        }

        override fun cancelReceive(peerItemId: String) = Unit
    }

    private class RecordingKeepAlive : TransferKeepAlive {
        var held = 0
        val descriptions = mutableListOf<String>()

        override fun hold(description: String): () -> Unit {
            held++
            descriptions += description
            return { held-- }
        }
    }
}
