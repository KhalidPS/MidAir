package com.kotlistine.transferingfilebyair.feature.shelf.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.ReceivedFileStore
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkedSlidePeerTest {

    private val link = FakeLink()
    private val shelf = InMemoryShelf()
    private val store = object : ReceivedFileStore {
        override suspend fun keep(location: String, name: String, mimeType: String): Result<String, DataError.Local> =
            Result.Success("content://downloads/$name")
    }
    private val incoming = IncomingFile(peerItemId = "9", name = "River Valley.jpg", mimeType = "image/jpeg", sizeBytes = 1)

    @Test
    fun `a sent file is announced with its transfer id before its bytes go`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, backgroundScope)

        val result = peer.send(OutgoingFile(itemId = "4", uri = "content://file/4"))

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(link.sent).containsExactly(SlideMessage.FileOffer(itemId = "4", transferId = 77))
        assertThat(link.sentFiles).containsExactly("content://file/4")
    }

    @Test
    fun `a received card's file is matched by its offer, kept, and put on the shelf`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, backgroundScope)
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))
        link.arrived[5] = "content://nearby/5"

        val result = peer.receive(incoming)

        assertThat(result).isEqualTo(Result.Success("kept-1"))
        assertThat(shelf.items.value.map { it.uri }).containsExactly("content://downloads/River Valley.jpg")
    }

    @Test
    fun `a file that never arrives fails the receive`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, backgroundScope)
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))

        val result = peer.receive(incoming)

        assertThat(result).isEqualTo(Result.Failure(DataError.Network.DISCONNECTED))
        assertThat(shelf.items.value.size).isEqualTo(0)
    }

    @Test
    fun `without an offer for the card the receive gives up`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, backgroundScope)

        assertThat(peer.receive(incoming)).isEqualTo(Result.Failure(DataError.Network.DISCONNECTED))
    }

    @Test
    fun `the peer counts as linked only while connected`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, backgroundScope)
        assertThat(peer.isLinked.first()).isEqualTo(false)

        link.state.value = LinkState.Connected(PeerDevice("ep", "Pixel"))

        assertThat(peer.isLinked.first()).isEqualTo(true)
    }
}

private class FakeLink : PeerLink {
    override val state = MutableStateFlow<LinkState>(LinkState.Idle)
    val inbound = MutableSharedFlow<SlideMessage>()
    val sent = mutableListOf<SlideMessage>()
    val sentFiles = mutableListOf<String>()

    /** Files that have fully arrived, by transfer id; anything else fails as if the link dropped. */
    val arrived = mutableMapOf<Long, String>()

    override val transport: Transport = object : Transport {
        override val incoming: Flow<SlideMessage> = inbound
        override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
            sent += message
            return Result.Success(Unit)
        }
    }

    override suspend fun sendFile(uri: String, announce: suspend (transferId: Long) -> Unit): EmptyResult<DataError.Network> {
        announce(77)
        sentFiles += uri
        return Result.Success(Unit)
    }

    override suspend fun receiveFile(transferId: Long): Result<String, DataError.Network> =
        arrived[transferId]?.let { Result.Success(it) } ?: Result.Failure(DataError.Network.DISCONNECTED)

    override suspend fun startSearching(): EmptyResult<DataError.Network> = Result.Success(Unit)
    override fun stopSearching() = Unit
    override suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network> = Result.Success(Unit)
    override suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network> = Result.Success(Unit)
    override fun decline(peer: PeerDevice) = Unit
    override fun disconnect() = Unit
}

private class InMemoryShelf : ShelfRepository {
    val items = MutableStateFlow<List<ShelfItem>>(emptyList())
    private var nextId = 1

    override fun observeItems(): Flow<List<ShelfItem>> = items

    override suspend fun addFile(uri: String): EmptyResult<DataError.Local> {
        items.update {
            listOf(ShelfItem("kept-${nextId++}", "file", "image/jpeg", 1, 0, uri)) + it
        }
        return Result.Success(Unit)
    }

    override suspend fun removeItem(id: String): EmptyResult<DataError.Local> = Result.Success(Unit)
    override suspend fun clear(): EmptyResult<DataError.Local> = Result.Success(Unit)
}
