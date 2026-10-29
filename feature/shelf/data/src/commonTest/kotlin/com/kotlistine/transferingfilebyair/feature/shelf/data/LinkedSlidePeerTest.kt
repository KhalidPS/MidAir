package com.kotlistine.transferingfilebyair.feature.shelf.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem
import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerDevice
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.ReceivedFileStore
import com.kotlistine.transferingfilebyair.feature.shelf.domain.Delivery
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

    private val media = FakeMedia()
    private val link = FakeLink(media)
    private val shelf = InMemoryShelf()
    private val geometry = object : LinkGeometry {
        override val link = MutableStateFlow(SlideLink())
        override fun update(link: SlideLink) {
            this.link.value = link
        }
    }
    private val store = object : ReceivedFileStore {
        override suspend fun keep(location: String, name: String, mimeType: String): Result<String, DataError.Local> =
            Result.Success("content://downloads/$name")
    }
    private val incoming = IncomingFile(peerItemId = "9", name = "River Valley.jpg", mimeType = "image/jpeg", sizeBytes = 1)

    @Test
    fun `a sent file is announced with its transfer id before its bytes go`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val result = peer.send(OutgoingFile(itemId = "4", uri = "content://file/4"))

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(link.sent).containsExactly(SlideMessage.FileOffer(itemId = "4", transferId = 77))
        assertThat(link.sentFiles).containsExactly("content://file/4")
    }

    @Test
    fun `a received card's file is matched by its offer, kept, and put on the shelf`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))
        link.arrived[5] = "content://nearby/5"

        val result = peer.receive(incoming)

        assertThat(result).isEqualTo(Result.Success("kept-1"))
        assertThat(shelf.items.value.map { it.uri }).containsExactly("content://downloads/River Valley.jpg")
    }

    @Test
    fun `progress of the incoming file is passed on`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))
        link.arrived[5] = "content://nearby/5"
        val progress = mutableListOf<Float>()

        peer.receive(incoming) { progress += it }

        assertThat(progress).containsExactly(0.5f, 1f)
    }

    @Test
    fun `stopping a receive cancels its transfer`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))

        peer.cancelReceive("9")

        assertThat(link.cancelledTransfers).containsExactly(5L)
    }

    @Test
    fun `stopping before the transfer is known fails it as cancelled once it is`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)
        peer.cancelReceive("9")
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))
        link.arrived[5] = "content://nearby/5"

        assertThat(peer.receive(incoming)).isEqualTo(Result.Failure(DataError.Network.CANCELLED))
        assertThat(link.cancelledTransfers).containsExactly(5L)
        assertThat(shelf.items.value.size).isEqualTo(0)
    }

    @Test
    fun `a file that never arrives fails the receive`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))

        val result = peer.receive(incoming)

        assertThat(result).isEqualTo(Result.Failure(DataError.Network.DISCONNECTED))
        assertThat(shelf.items.value.size).isEqualTo(0)
    }

    @Test
    fun `without an offer for the card the receive gives up`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        assertThat(peer.receive(incoming)).isEqualTo(Result.Failure(DataError.Network.DISCONNECTED))
    }

    // region A card that carried its playing video: the receiver may take the file from what it read.

    private val connected = LinkState.Connected(PeerDevice("ep", "Pixel"))
    private val video = OutgoingFile(itemId = "4", uri = "content://file/4", peerMayFetch = true)

    private fun route(route: SlideMessage.FileRoute.Route) = SlideMessage.FileRoute("4", route)

    @Test
    fun `a file the peer fetches itself is done once the peer has it, and never sent`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val sending = async { peer.send(video) }
        link.inbound.emit(route(SlideMessage.FileRoute.Route.FETCHING))
        link.inbound.emit(route(SlideMessage.FileRoute.Route.FETCHED))

        assertThat(sending.await()).isEqualTo(Result.Success(Unit))
        assertThat(link.sentFiles).isEmpty()
    }

    @Test
    fun `a file the peer asks for is sent as usual`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val sending = async { peer.send(video) }
        link.inbound.emit(route(SlideMessage.FileRoute.Route.SEND))

        assertThat(sending.await()).isEqualTo(Result.Success(Unit))
        assertThat(link.sentFiles).containsExactly("content://file/4")
    }

    @Test
    fun `a fetch that fails on the peer's side gets the file sent after all`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val sending = async { peer.send(video) }
        link.inbound.emit(route(SlideMessage.FileRoute.Route.FETCHING))
        link.inbound.emit(route(SlideMessage.FileRoute.Route.SEND))

        assertThat(sending.await()).isEqualTo(Result.Success(Unit))
        assertThat(link.sentFiles).containsExactly("content://file/4")
    }

    @Test
    fun `a peer that never says how gets the file sent`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val result = peer.send(video)

        assertThat(result).isEqualTo(Result.Success(Unit))
        assertThat(link.sentFiles).containsExactly("content://file/4")
    }

    @Test
    fun `a fetch stopped on the peer's side fails the send as cancelled`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val sending = async { peer.send(video) }
        link.inbound.emit(route(SlideMessage.FileRoute.Route.FETCHING))
        link.inbound.emit(route(SlideMessage.FileRoute.Route.CANCELLED))

        assertThat(sending.await()).isEqualTo(Result.Failure(DataError.Network.CANCELLED))
        assertThat(link.sentFiles).isEmpty()
    }

    @Test
    fun `the link going while the peer fetches fails the send`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val sending = async { peer.send(video) }
        link.inbound.emit(route(SlideMessage.FileRoute.Route.FETCHING))
        link.state.value = LinkState.Idle

        assertThat(sending.await()).isEqualTo(Result.Failure(DataError.Network.DISCONNECTED))
        assertThat(link.sentFiles).isEmpty()
    }

    private val fetched = incoming.copy(delivery = Delivery.FETCH)

    @Test
    fun `a fetched file is kept, put on the shelf and played from there, and only then is the peer told`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val result = peer.receive(fetched)

        assertThat(result).isEqualTo(Result.Success("kept-1"))
        assertThat(shelf.items.value.map { it.uri }).containsExactly("content://downloads/River Valley.jpg")
        assertThat(media.settled).containsExactly("9" to "content://downloads/River Valley.jpg")
        assertThat(link.sent).containsExactly(
            SlideMessage.FileRoute("9", SlideMessage.FileRoute.Route.FETCHING),
            SlideMessage.FileRoute("9", SlideMessage.FileRoute.Route.FETCHED),
        )
    }

    @Test
    fun `a fetch that fails with the link up asks for the file and takes it the usual way`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        media.fetchResult = { Result.Failure(DataError.Local.UNKNOWN) }
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))
        link.arrived[5] = "content://nearby/5"

        val result = peer.receive(fetched)

        assertThat(result).isEqualTo(Result.Success("kept-1"))
        assertThat(link.sent).containsExactly(
            SlideMessage.FileRoute("9", SlideMessage.FileRoute.Route.FETCHING),
            SlideMessage.FileRoute("9", SlideMessage.FileRoute.Route.SEND),
        )
        assertThat(media.forgotten).containsExactly("9")
        // The video was read from the peer: it goes on from the kept file.
        assertThat(media.settled).containsExactly("9" to "content://downloads/River Valley.jpg")
    }

    @Test
    fun `a fetch that fails because the link went fails the receive`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        media.fetchResult = {
            link.state.value = LinkState.Idle
            Result.Failure(DataError.Network.DISCONNECTED)
        }
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val result = peer.receive(fetched)

        assertThat(result).isEqualTo(Result.Failure(DataError.Network.DISCONNECTED))
        assertThat(link.sent).containsExactly(SlideMessage.FileRoute("9", SlideMessage.FileRoute.Route.FETCHING))
        assertThat(shelf.items.value.size).isEqualTo(0)
    }

    @Test
    fun `stopping a fetch tells the peer and fails as cancelled`() = runTest(UnconfinedTestDispatcher()) {
        link.state.value = connected
        media.fetchResult = { CompletableDeferred<Nothing>().await() }
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)

        val receiving = async { peer.receive(fetched) }
        peer.cancelReceive("9")

        assertThat(receiving.await()).isEqualTo(Result.Failure(DataError.Network.CANCELLED))
        assertThat(link.sent.last()).isEqualTo(SlideMessage.FileRoute("9", SlideMessage.FileRoute.Route.CANCELLED))
        assertThat(media.forgotten).containsExactly("9")
    }

    @Test
    fun `a card whose video wasn't played here asks the peer to send its file`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)
        link.inbound.emit(SlideMessage.FileOffer(itemId = "9", transferId = 5))
        link.arrived[5] = "content://nearby/5"

        val result = peer.receive(incoming.copy(delivery = Delivery.ASK_TO_SEND))

        assertThat(result).isEqualTo(Result.Success("kept-1"))
        assertThat(link.sent).containsExactly(SlideMessage.FileRoute("9", SlideMessage.FileRoute.Route.SEND))
        assertThat(media.settled).isEmpty()
    }

    // endregion

    @Test
    fun `the peer counts as linked only while connected`() = runTest(UnconfinedTestDispatcher()) {
        val peer = LinkedSlidePeer(link, shelf, store, geometry, backgroundScope)
        assertThat(peer.isLinked.first()).isEqualTo(false)

        link.state.value = LinkState.Connected(PeerDevice("ep", "Pixel"))

        assertThat(peer.isLinked.first()).isEqualTo(true)
    }
}

private class FakeLink(override val media: MediaChannel? = null) : PeerLink {
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

    val cancelledTransfers = mutableListOf<Long>()

    override suspend fun receiveFile(transferId: Long, onProgress: (Float) -> Unit): Result<String, DataError.Network> {
        val location = arrived[transferId] ?: return Result.Failure(DataError.Network.DISCONNECTED)
        onProgress(0.5f)
        onProgress(1f)
        return Result.Success(location)
    }

    override fun cancelTransfer(transferId: Long) {
        cancelledTransfers += transferId
    }

    override suspend fun startSearching(): EmptyResult<DataError.Network> = Result.Success(Unit)
    override fun stopSearching() = Unit
    override suspend fun reconnect() = Unit
    override fun pauseReconnecting() = Unit
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

private class FakeMedia : MediaChannel {
    override val isFast = true

    /** What fetching a file returns. */
    var fetchResult: suspend () -> Result<String, DataError> = { Result.Success("file:///cache/fetched-1") }
    val settled = mutableListOf<Pair<String, String>>()
    val forgotten = mutableListOf<String>()

    override fun share(fileId: String, uri: String): String? = null
    override fun unshare(fileId: String) = Unit
    override suspend fun canReach(url: String): Boolean = false
    override suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network> =
        Result.Failure(DataError.Network.DISCONNECTED)

    override suspend fun fetch(fileId: String, sizeBytes: Long, onProgress: (Float) -> Unit): Result<String, DataError> =
        fetchResult()

    override fun settle(fileId: String, uri: String) {
        settled += fileId to uri
    }

    override fun forget(fileId: String) {
        forgotten += fileId
    }
}
