package com.kotlistine.transferingfilebyair.core.transfer.lan

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/** Two real links on this machine, over 127.0.0.1, found through an in-memory stand-in for mDNS. */
class LanPeerLinkTest {
    private val folder: File = Files.createTempDirectory("lan-link-test").toFile()
    private val network = FakeNetwork()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** The memory each link was made with, by name: what the link keeps, as it keeps it. */
    private val memories = mutableMapOf<String, LanMemory>()

    /** The laptop decides where the phone sits, as in the app. */
    private fun link(name: String) = LanPeerLink(
        memory = LanMemory(File(folder, "$name/link.properties")).also { memories[name] = it },
        discovery = network.discovery(),
        files = DiskLanFiles(File(folder, "$name/incoming")),
        ownName = name,
        ownsPlacement = name == "laptop",
    )

    @Test
    fun `two devices pair once both users accept the same code`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        assertThat((laptop.state.value as LinkState.Connected).peer.name).isEqualTo("phone")
        assertThat((phone.state.value as LinkState.Connected).peer.name).isEqualTo("laptop")
    }

    @Test
    fun `scanning the laptop's code pairs them without asking either user`() = runBlocking<Unit> {
        val laptop = link("laptop")
        val phone = link("phone")
        val invite = laptop.invite(listOf("10.255.255.1", "127.0.0.1"))!!
        // Neither is searching: the code alone brings them together, trying each address in turn.
        phone.joinInvite(invite.toText())
        withTimeout(Timeout) { laptop.state.first { it is LinkState.Connected } }
        withTimeout(Timeout) { phone.state.first { it is LinkState.Connected } }
        withTimeout(Timeout) {
            while (memories.getValue("phone").peer == null || memories.getValue("laptop").peer == null) {
                kotlinx.coroutines.delay(20)
            }
        }
        assertThat(memories.getValue("phone").peer!!.key.toList())
            .isEqualTo(memories.getValue("laptop").peer!!.key.toList())
    }

    @Test
    fun `a code with the wrong secret doesn't pair`() = runBlocking<Unit> {
        val laptop = link("laptop")
        val phone = link("phone")
        val invite = laptop.invite(listOf("127.0.0.1"))!!
        val forged = LanInvite(invite.hosts, invite.port, invite.deviceId, invite.name, ByteArray(InviteTokenBytes))
        phone.joinInvite(forged.toText())
        assertThat(withTimeout(Timeout) { phone.state.first { it is LinkState.Failed } })
            .isEqualTo(LinkState.Failed(DataError.Network.DISCONNECTED))
        assertThat(laptop.state.value).isEqualTo(LinkState.Idle)
    }

    @Test
    fun `a withdrawn code no longer pairs`() = runBlocking<Unit> {
        val laptop = link("laptop")
        val phone = link("phone")
        val invite = laptop.invite(listOf("127.0.0.1"))!!
        laptop.withdrawInvite()
        kotlinx.coroutines.delay(100)
        phone.joinInvite(invite.toText())
        withTimeout(Timeout) { phone.state.first { it is LinkState.Failed } }
        assertThat(laptop.state.value).isEqualTo(LinkState.Idle)
    }

    @Test
    fun `the phone learns where it sits from the laptop, at linking and on every change`() = runBlocking<Unit> {
        val (laptop, _) = pair(beforeLinking = { laptop -> laptop.placePeer(ExitEdge.RIGHT, verticalOffsetMm = 40f) })
        val phoneMemory = memories.getValue("phone")
        withTimeout(Timeout) { phoneMemory.link.first { it.peerSide != null } }
        assertThat(phoneMemory.link.value.peerSide).isEqualTo(ExitEdge.LEFT)
        assertThat(phoneMemory.link.value.verticalOffsetMm).isEqualTo(-40f)

        laptop.placePeer(ExitEdge.LEFT, verticalOffsetMm = 25f)
        withTimeout(Timeout) { phoneMemory.link.first { it.peerSide == ExitEdge.RIGHT } }
        assertThat(phoneMemory.link.value.verticalOffsetMm).isEqualTo(-25f)
        assertThat(memories.getValue("laptop").link.value.peerSide).isEqualTo(ExitEdge.LEFT)
    }

    @Test
    fun `a card message goes across`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        val heard = async { laptop.transport.incoming.first() }
        // The collector must be listening before the message goes.
        kotlinx.coroutines.delay(100)
        phone.transport.send(SlideMessage.Ping(sentAtMillis = 1234))
        assertThat(withTimeout(Timeout) { heard.await() }).isEqualTo(SlideMessage.Ping(sentAtMillis = 1234))
    }

    @Test
    fun `a file arrives whole, both ways`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        listOf(phone to laptop, laptop to phone).forEach { (sender, receiver) ->
            val original = File(folder, "original-${Random.nextInt()}.bin").apply {
                writeBytes(Random.nextBytes(700_000))
            }
            val announced = CompletableDeferred<Long>()
            val sending = async {
                sender.sendFile(original.toURI().toString(), announce = { announced.complete(it) })
            }
            val location = withTimeout(Timeout) { receiver.receiveFile(announced.await()) }
            assertThat(withTimeout(Timeout) { sending.await() }).isEqualTo(Result.Success(Unit))
            val kept = File(URI((location as Result.Success).data))
            assertThat(kept.readBytes().toList()).isEqualTo(original.readBytes().toList())
        }
    }

    @Test
    fun `a stopped file fails on both ends`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        val original = File(folder, "big.bin").apply { writeBytes(Random.nextBytes(20_000_000)) }
        val announced = CompletableDeferred<Long>()
        val sending = async { phone.sendFile(original.toURI().toString(), announce = { announced.complete(it) }) }
        val transferId = withTimeout(Timeout) { announced.await() }
        val receiving = async { laptop.receiveFile(transferId) }
        laptop.cancelTransfer(transferId)
        assertThat(withTimeout(Timeout) { receiving.await() }).isEqualTo(Result.Failure(DataError.Network.CANCELLED))
        assertThat(withTimeout(Timeout) { sending.await() }).isEqualTo(Result.Failure(DataError.Network.CANCELLED))
    }

    @Test
    fun `a declined pairing leaves both looking, unpaired`() = runBlocking<Unit> {
        val laptop = link("laptop")
        val phone = link("phone")
        laptop.startSearching()
        phone.startSearching()
        val found = withTimeout(Timeout) { phone.state.first { it is LinkState.Found } } as LinkState.Found
        phone.connect(found.peer)
        val asking = withTimeout(Timeout) { laptop.state.first { it is LinkState.Incoming } } as LinkState.Incoming
        laptop.decline(asking.peer)
        withTimeout(Timeout) { phone.state.first { it is LinkState.Found || it is LinkState.Searching } }
        assertThat(LanMemory(File(folder, "phone/link.properties")).peer).isNull()
    }

    @Test
    fun `paired devices link up again by themselves, without asking`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        // Both stored the same key from the pairing.
        val laptopMemory = LanMemory(File(folder, "laptop/link.properties"))
        val phoneMemory = LanMemory(File(folder, "phone/link.properties"))
        assertThat(laptopMemory.peer).isNotNull()
        assertThat(laptopMemory.peer!!.key.toList()).isEqualTo(phoneMemory.peer!!.key.toList())

        // The apps restart: new links, the same memories.
        laptop.stopSearching()
        phone.stopSearching()
        network.clear()
        val laptopAgain = link("laptop")
        val phoneAgain = link("phone")
        laptopAgain.reconnect()
        phoneAgain.reconnect()
        withTimeout(Timeout) { laptopAgain.state.first { it is LinkState.Connected } }
        withTimeout(Timeout) { phoneAgain.state.first { it is LinkState.Connected } }
    }

    /** Pairs a laptop and a phone the way two users would: the phone asks, both accept the code. */
    private suspend fun pair(beforeLinking: (laptop: LanPeerLink) -> Unit = {}): Pair<LanPeerLink, LanPeerLink> {
        val laptop = link("laptop")
        val phone = link("phone")
        beforeLinking(laptop)
        laptop.startSearching()
        phone.startSearching()
        val found = withTimeout(Timeout) { phone.state.first { it is LinkState.Found } } as LinkState.Found
        assertThat(found.peer.name).isEqualTo("laptop")
        phone.connect(found.peer)
        val onLaptop = withTimeout(Timeout) { laptop.state.first { it is LinkState.Incoming } } as LinkState.Incoming
        val onPhone = withTimeout(Timeout) { phone.state.first { it is LinkState.Incoming } } as LinkState.Incoming
        assertThat(onLaptop.code).isEqualTo(onPhone.code)
        laptop.accept(onLaptop.peer)
        phone.accept(onPhone.peer)
        withTimeout(Timeout) { laptop.state.first { it is LinkState.Connected } }
        withTimeout(Timeout) { phone.state.first { it is LinkState.Connected } }
        // The key is handed out right after linking up.
        withTimeout(Timeout) {
            while (LanMemory(File(folder, "phone/link.properties")).peer == null ||
                LanMemory(File(folder, "laptop/link.properties")).peer == null
            ) {
                kotlinx.coroutines.delay(20)
            }
        }
        return laptop to phone
    }

    private class FakeNetwork {
        private val services = MutableStateFlow<Map<String, LanService>>(emptyMap())

        fun clear() {
            services.value = emptyMap()
        }

        fun discovery() = object : LanDiscovery {
            private var mine: String? = null

            override fun advertise(deviceId: String, name: String, port: Int) {
                mine = deviceId
                services.update { it + (deviceId to LanService(deviceId, name, "127.0.0.1", port)) }
            }

            override fun stopAdvertising() {
                mine?.let { id -> services.update { it - id } }
            }

            override fun browse(): Flow<List<LanService>> = services.map { it.values.toList() }
        }
    }

    private companion object {
        const val Timeout = 10_000L
    }
}
