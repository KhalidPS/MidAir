package com.kotlistine.midair.core.transfer.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult
import com.kotlistine.midair.core.domain.util.Result
import com.kotlistine.midair.core.transfer.domain.InviteLink
import com.kotlistine.midair.core.transfer.domain.LinkGeometry
import com.kotlistine.midair.core.transfer.domain.LinkState
import com.kotlistine.midair.core.transfer.domain.PeerDevice
import com.kotlistine.midair.core.transfer.domain.PeerLink
import com.kotlistine.midair.core.transfer.domain.SlideLink
import com.kotlistine.midair.core.transfer.domain.SlideMessage
import com.kotlistine.midair.core.transfer.domain.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CombinedPeerLinkTest {
    private val phone = PeerDevice("endpoint-1", "Pixel")
    private val laptop = PeerDevice("lan:abcd", "Laptop")
    private val nearby = FakeLink("nearby")
    private val lan = FakeLink("lan")

    private fun TestScope.combined() = CombinedPeerLink(
        primary = nearby,
        secondary = lan,
        scope = backgroundScope,
    )

    @Test
    fun `with the second link idle, the state is exactly the first link's`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        listOf(
            LinkState.Searching,
            LinkState.Found(phone),
            LinkState.Incoming(phone, "1234"),
            LinkState.Connecting(phone, "1234"),
            LinkState.Connected(phone),
            LinkState.Failed(DataError.Network.LOCATION_OFF),
            LinkState.Idle,
        ).forEach { state ->
            nearby.state.value = state
            assertThat(link.state.value).isEqualTo(state)
        }
    }

    @Test
    fun `with the second link idle, messages and files go through the first`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        link.transport.send(SlideMessage.Ping(1))
        link.sendFile("content://a", announce = {})
        link.cancelTransfer(7)
        assertThat(nearby.calls).containsExactly("send Ping(sentAtMillis=1)", "sendFile content://a", "cancel 7")
        assertThat(lan.calls).isEqualTo(emptyList<String>())
    }

    @Test
    fun `a laptop found on the network is offered while no phone is`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        nearby.state.value = LinkState.Searching
        lan.state.value = LinkState.Found(laptop)
        assertThat(link.state.value).isEqualTo(LinkState.Found(laptop))
    }

    @Test
    fun `of a phone and a laptop found together, the one found last is offered`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        nearby.state.value = LinkState.Found(phone)
        lan.state.value = LinkState.Found(laptop)
        assertThat(link.state.value).isEqualTo(LinkState.Found(laptop))
        nearby.state.value = LinkState.Searching
        nearby.state.value = LinkState.Found(phone)
        assertThat(link.state.value).isEqualTo(LinkState.Found(phone))
    }

    @Test
    fun `connecting goes to the link that found the device, and the other stops looking`() =
        runTest(UnconfinedTestDispatcher()) {
            val link = combined()
            lan.state.value = LinkState.Found(laptop)
            link.connect(laptop)
            assertThat(lan.calls).containsExactly("connect Laptop")
            assertThat(nearby.calls).containsExactly("stopSearching")
        }

    @Test
    fun `once the laptop is linked, everything goes to it and the phones' link stops looking`() =
        runTest(UnconfinedTestDispatcher()) {
            val link = combined()
            lan.state.value = LinkState.Connected(laptop)
            assertThat(link.state.value).isEqualTo(LinkState.Connected(laptop))
            assertThat(link.usesSecondary.value).isTrue()
            assertThat(nearby.calls).containsExactly("pauseReconnecting", "stopSearching")

            nearby.calls.clear()
            link.transport.send(SlideMessage.Ping(2))
            link.receiveFile(9)
            assertThat(lan.calls).containsExactly("send Ping(sentAtMillis=2)", "receiveFile 9")
            assertThat(nearby.calls).isEqualTo(emptyList<String>())
        }

    @Test
    fun `when the laptop's link ends, the phones' link looks again`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        link.reconnect()
        lan.state.value = LinkState.Connected(laptop)
        nearby.calls.clear()
        lan.state.value = LinkState.Idle
        assertThat(nearby.calls).containsExactly("reconnect")
        assertThat(link.usesSecondary.value).isFalse()
    }

    @Test
    fun `a failed laptop pairing shows, even while phones are still looked for`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        nearby.state.value = LinkState.Searching
        lan.state.value = LinkState.Found(laptop)
        link.connect(laptop)
        lan.state.value = LinkState.Failed(DataError.Network.DISCONNECTED)
        assertThat(link.state.value).isEqualTo(LinkState.Failed(DataError.Network.DISCONNECTED))
    }

    @Test
    fun `a scanned code goes to the laptop's link, and the phones' link stops looking`() =
        runTest(UnconfinedTestDispatcher()) {
            val link = combined()
            link.joinInvite("airslide:1?code")
            assertThat(lan.calls).containsExactly("joinInvite airslide:1?code")
            assertThat(nearby.calls).containsExactly("stopSearching")
            // Its failure is the one to show.
            nearby.state.value = LinkState.Searching
            lan.state.value = LinkState.Failed(DataError.Network.DISCONNECTED)
            assertThat(link.state.value).isEqualTo(LinkState.Failed(DataError.Network.DISCONNECTED))
        }

    @Test
    fun `the screens line up by swipe unless the laptop is the live link`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        lan.linesUp = false
        assertThat(link.linesUpBySwipe).isTrue()
        lan.state.value = LinkState.Connected(laptop)
        assertThat(link.linesUpBySwipe).isFalse()
    }

    @Test
    fun `searching works if either link can search`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        nearby.searchResult = Result.Failure(DataError.Network.PERMISSION_MISSING)
        assertThat(link.startSearching()).isEqualTo(Result.Success(Unit))
        lan.searchResult = Result.Failure(DataError.Network.UNKNOWN)
        assertThat(link.startSearching()).isEqualTo(Result.Failure(DataError.Network.PERMISSION_MISSING))
    }

    @Test
    fun `the line-up follows the live link`() = runTest(UnconfinedTestDispatcher()) {
        val link = combined()
        val phoneSide = FakeGeometry(SlideLink(gapMm = 8f))
        val laptopSide = FakeGeometry(SlideLink(gapMm = 20f))
        val geometry = CombinedLinkGeometry(phoneSide, laptopSide, link.usesSecondary, backgroundScope)
        assertThat(geometry.link.value.gapMm).isEqualTo(8f)
        lan.state.value = LinkState.Connected(laptop)
        assertThat(geometry.link.value.gapMm).isEqualTo(20f)
        geometry.update(SlideLink(gapMm = 25f))
        assertThat(laptopSide.link.value.gapMm).isEqualTo(25f)
        assertThat(phoneSide.link.value.gapMm).isEqualTo(8f)
    }

    private class FakeGeometry(initial: SlideLink) : LinkGeometry {
        private val current = MutableStateFlow(initial)
        override val link: StateFlow<SlideLink> = current.asStateFlow()
        override fun update(link: SlideLink) {
            current.value = link
        }
    }

    private class FakeLink(val label: String) : PeerLink, InviteLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Idle)
        val calls = mutableListOf<String>()
        var searchResult: EmptyResult<DataError.Network> = Result.Success(Unit)
        var linesUp = true
        override val linesUpBySwipe: Boolean get() = linesUp

        override val transport: Transport = object : Transport {
            override val incoming = MutableSharedFlow<SlideMessage>()
            override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> {
                calls += "send $message"
                return Result.Success(Unit)
            }
        }

        override suspend fun startSearching(): EmptyResult<DataError.Network> = searchResult
        override fun stopSearching() {
            calls += "stopSearching"
        }

        override suspend fun reconnect() {
            calls += "reconnect"
        }

        override fun pauseReconnecting() {
            calls += "pauseReconnecting"
        }

        override suspend fun connect(peer: PeerDevice): EmptyResult<DataError.Network> {
            calls += "connect ${peer.name}"
            return Result.Success(Unit)
        }

        override suspend fun accept(peer: PeerDevice): EmptyResult<DataError.Network> = Result.Success(Unit)
        override fun decline(peer: PeerDevice) = Unit
        override fun disconnect() = Unit

        override suspend fun sendFile(
            uri: String,
            announce: suspend (transferId: Long) -> Unit,
            onProgress: (bytesSent: Long) -> Unit,
        ): EmptyResult<DataError.Network> {
            calls += "sendFile $uri"
            return Result.Success(Unit)
        }

        override suspend fun receiveFile(
            transferId: Long,
            onProgress: (bytesReceived: Long) -> Unit,
        ): Result<String, DataError.Network> {
            calls += "receiveFile $transferId"
            return Result.Success("file")
        }

        override suspend fun joinInvite(text: String): EmptyResult<DataError.Network> {
            calls += "joinInvite $text"
            return Result.Success(Unit)
        }

        override fun cancelTransfer(transferId: Long) {
            calls += "cancel $transferId"
        }
    }
}
