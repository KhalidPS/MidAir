package com.kotlistine.transferingfilebyair.core.transfer.lan

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    fun `a shared file is read from any byte on, both ways`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        listOf(phone to laptop, laptop to phone).forEach { (owner, reader) ->
            val original = randomFile(900_000)
            owner.media.share("video", original.toURI().toString())
            val opened = withTimeout(Timeout) { reader.media.open("video", offset = 300_001) }
            val stream = (opened as Result.Success).data
            val bytes = withTimeout(Timeout) { withContext(Dispatchers.IO) { stream.readAll() } }
            stream.close()
            assertThat(bytes.toList()).isEqualTo(original.readBytes().drop(300_001))
        }
    }

    @Test
    fun `a file is pulled whole, and the owner counts what went`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        val original = randomFile(1_300_000)
        phone.media.share("item-1", original.toURI().toString())
        val pulled = withTimeout(Timeout) { laptop.media.fetch("item-1", original.length()) }
        val kept = File(URI((pulled as Result.Success).data))
        assertThat(kept.readBytes().toList()).isEqualTo(original.readBytes().toList())
        assertThat(phone.media.servedBytes("item-1")).isEqualTo(original.length())
    }

    @Test
    fun `what a player already read isn't pulled again`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        val original = randomFile(2_000_000)
        phone.media.share("video", original.toURI().toString())
        val stream = (withTimeout(Timeout) { laptop.media.open("video", offset = 0) } as Result.Success).data
        withContext(Dispatchers.IO) { stream.readExactly(700_000) }
        val pulled = withTimeout(Timeout) { laptop.media.fetch("video", original.length()) }
        val file = File(URI((pulled as Result.Success).data))
        assertThat(file.readBytes().toList()).isEqualTo(original.readBytes().toList())
        assertThat(phone.media.servedBytes("video")).isLessThanOrEqualTo(original.length() - 700_000)
        // The player reads on from the pulled file, from where it was.
        val rest = withTimeout(Timeout) { withContext(Dispatchers.IO) { stream.readAll() } }
        stream.close()
        assertThat(rest.toList()).isEqualTo(original.readBytes().drop(700_000))
    }

    @Test
    fun `a file that isn't shared can't be read`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        val original = randomFile(10_000)
        phone.media.share("item-1", original.toURI().toString())
        phone.media.unshare("item-1")
        val pulled = withTimeout(Timeout) { laptop.media.fetch("item-1", original.length()) }
        assertThat(pulled).isEqualTo(Result.Failure(DataError.Network.DISCONNECTED))
    }

    @Test
    fun `a pull asks for nothing while a card is in the air`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        val original = randomFile(600_000)
        phone.media.share("item-1", original.toURI().toString())
        laptop.media.holdFetches(true)
        val pulling = async { laptop.media.fetch("item-1", original.length()) }
        kotlinx.coroutines.delay(300)
        assertThat(phone.media.servedBytes("item-1")).isEqualTo(0L)
        laptop.media.holdFetches(false)
        val pulled = withTimeout(Timeout) { pulling.await() }
        assertThat(File(URI((pulled as Result.Success).data)).length()).isEqualTo(original.length())
    }

    @Test
    fun `once the file is kept here, a stream reads on from this copy`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        val original = randomFile(2_000_000)
        phone.media.share("video", original.toURI().toString())
        val stream = (withTimeout(Timeout) { laptop.media.open("video", offset = 0) } as Result.Success).data
        val start = withContext(Dispatchers.IO) { stream.readExactly(1_000) }
        assertThat(start.toList()).isEqualTo(original.readBytes().take(1_000))
        // The copy kept here differs, so where the bytes come from shows; the phone no longer serves it.
        val copy = File(folder, "kept.bin").apply { writeBytes(ByteArray(2_000_000) { 7 }) }
        laptop.media.settle("video", copy.toURI().toString())
        phone.media.unshare("video")
        val rest = withTimeout(Timeout) { withContext(Dispatchers.IO) { stream.readAll() } }
        stream.close()
        // What was already on its way may still come from the phone; the end comes from the copy.
        assertThat(rest.size).isEqualTo(1_999_000)
        assertThat(rest.takeLast(100).toSet()).isEqualTo(setOf<Byte>(7))
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

    @Test
    fun `scanning the code of a laptop paired before links them again`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        val oldKey = memories.getValue("laptop").peer!!.key.toList()

        // The apps restart and don't find each other on the network: the user falls back on the code.
        laptop.stopSearching()
        phone.stopSearching()
        network.clear()
        val laptopAgain = link("laptop")
        val phoneAgain = link("phone")
        val invite = laptopAgain.invite(listOf("127.0.0.1"))!!
        phoneAgain.joinInvite(invite.toText())

        withTimeout(Timeout) { laptopAgain.state.first { it is LinkState.Connected } }
        withTimeout(Timeout) { phoneAgain.state.first { it is LinkState.Connected } }
        // The scan is a new pairing: both end up with the same new key.
        withTimeout(Timeout) {
            while (true) {
                val onLaptop = memories.getValue("laptop").peer?.key?.toList()
                val onPhone = memories.getValue("phone").peer?.key?.toList()
                if (onLaptop != null && onLaptop != oldKey && onLaptop == onPhone) break
                kotlinx.coroutines.delay(20)
            }
        }
    }

    @Test
    fun `scanning the code pairs a phone that forgot the laptop, though the laptop remembers it`() = runBlocking<Unit> {
        val (laptop, phone) = pair()
        laptop.stopSearching()
        phone.stopSearching()
        network.clear()
        // The phone keeps who it is, and forgets the laptop.
        val phoneFile = File(folder, "phone/link.properties")
        phoneFile.writeText(phoneFile.readLines().filterNot { it.startsWith("peer_") }.joinToString("\n"))
        val laptopAgain = link("laptop")
        val phoneAgain = link("phone")
        assertThat(memories.getValue("phone").peer).isNull()
        assertThat(memories.getValue("laptop").peer).isNotNull()

        val invite = laptopAgain.invite(listOf("127.0.0.1"))!!
        phoneAgain.joinInvite(invite.toText())

        withTimeout(Timeout) { laptopAgain.state.first { it is LinkState.Connected } }
        withTimeout(Timeout) { phoneAgain.state.first { it is LinkState.Connected } }
    }

    /** Pairs a laptop and a phone the way two users would: the phone asks, both accept the code. */
    private fun randomFile(size: Int): File =
        File(folder, "original-${Random.nextInt()}.bin").apply { writeBytes(Random.nextBytes(size)) }

    private fun ByteStream.readAll(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
        }
    }

    private fun ByteStream.readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var at = 0
        while (at < count) {
            val read = read(bytes, at, count - at)
            check(read > 0)
            at += read
        }
        return bytes
    }

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
        // The key is handed out right after linking up. Read from each link's own memory: a second one on the same
        // file could read it between the old one's removal and the new one's rename (Windows), and write its own.
        withTimeout(Timeout) {
            while (memories.getValue("phone").peer == null || memories.getValue("laptop").peer == null) {
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
