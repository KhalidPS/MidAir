package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test

/** Two phones' card-message links, on this machine's loopback. */
class LanMessageLinkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val arrived = CopyOnWriteArrayList<String>()
    private val receiver = LanMessageLink(scope, onMessage = { arrived += it.decodeToString() }, log = {})
    private val sender = LanMessageLink(scope, onMessage = {}, log = {})

    @AfterTest
    fun tearDown() {
        receiver.close()
        sender.close()
        scope.cancel()
    }

    private fun awaitArrived(count: Int) {
        val until = System.currentTimeMillis() + 5_000
        while (arrived.size < count && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test
    fun `messages reach the other phone in order once connected`() = runBlocking {
        val offer = receiver.listen("127.0.0.1")
        assertThat(offer).isNotNull()

        assertThat(sender.connect(offer!!)).isTrue()
        assertThat(sender.isUp).isTrue()
        repeat(50) { assertThat(sender.send("move $it".encodeToByteArray())).isTrue() }

        awaitArrived(50)
        assertThat(arrived.toList()).isEqualTo((0 until 50).map { "move $it" })
    }

    @Test
    fun `a connection without the token gets nothing through`() = runBlocking {
        val offer = receiver.listen("127.0.0.1")!!
        val stranger = LanMessageLink(scope, onMessage = {}, log = {})

        stranger.connect(LinkFrame.LanOffer(offer.address, offer.port, ByteArray(LanTokenBytes)))
        stranger.send("injected".encodeToByteArray())
        // The real phone still gets through afterwards.
        sender.connect(offer)
        sender.send("real".encodeToByteArray())

        awaitArrived(1)
        Thread.sleep(100)
        assertThat(arrived.toList()).containsExactly("real")
        stranger.close()
    }

    @Test
    fun `without a listener to connect to, sends say so and go the other way`() = runBlocking {
        val offer = receiver.listen("127.0.0.1")!!
        receiver.close()

        assertThat(sender.connect(offer)).isFalse()
        assertThat(sender.send("move".encodeToByteArray())).isFalse()
    }

    @Test
    fun `a message too big to be one closes the connection`() = runBlocking {
        val offer = receiver.listen("127.0.0.1")!!
        Socket(offer.address, offer.port).use { socket ->
            val output = java.io.DataOutputStream(socket.getOutputStream())
            output.write(offer.token)
            output.writeInt(Int.MAX_VALUE)
            output.flush()
            Thread.sleep(200)
            // The listener dropped it: what follows is never read as a message.
            assertThat(socket.getInputStream().read()).isEqualTo(-1)
        }
        assertThat(arrived.toList()).isEqualTo(emptyList<String>())
    }
}
