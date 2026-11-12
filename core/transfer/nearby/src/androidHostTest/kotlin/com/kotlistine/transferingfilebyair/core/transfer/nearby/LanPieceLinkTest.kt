package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test

/** Two phones' piece links, on this machine's loopback: one shares a file, the other asks for pieces of it. */
class LanPieceLinkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The sharing phone's file "video": 300,000 bytes that say where they are. */
    private val file = ByteArray(300_000) { (it % 251).toByte() }

    /** Where the sharing phone's file may be read up to; past it a piece is cut short, at or after it refused. */
    @Volatile
    private var openUntil = file.size.toLong()

    private val served = ConcurrentHashMap<String, Long>()

    private val sharer = LanPieceLink(
        scope,
        serve = { fileId, offset, length ->
            val end = minOf(offset + length, openUntil)
            if (fileId != "video" || end <= offset) {
                null
            } else {
                ServedPiece(ByteArrayInputStream(file, offset.toInt(), (end - offset).toInt()), end - offset)
            }
        },
        onServed = { fileId, bytes -> served.merge(fileId, bytes, Long::plus) },
        log = {},
    )
    private val asker = LanPieceLink(scope, serve = { _, _, _ -> null }, log = {})

    @AfterTest
    fun tearDown() {
        sharer.close()
        asker.close()
        scope.cancel()
    }

    private fun connected(): LinkFrame.PieceOffer {
        val offer = sharer.listen("127.0.0.1")
        assertThat(offer).isNotNull()
        assertThat(asker.connect(offer!!)).isTrue()
        return offer
    }

    private fun LanPieceLink.read(fileId: String, offset: Long, length: Long): ByteArray? =
        (open(fileId, offset, length) as? PieceAnswer.Coming)?.input?.use { it.readBytes() }

    @Test
    fun `a piece asked for arrives byte for byte`() {
        connected()

        val piece = asker.read("video", offset = 1_000, length = 50_000)

        assertThat(piece?.toList()).isEqualTo(file.copyOfRange(1_000, 51_000).toList())
    }

    @Test
    fun `pieces one after the other share the connections opened at the start`() {
        connected()
        val openedAtStart = asker.connectionsOpened.get()

        repeat(20) { i -> assertThat(asker.read("video", i * 10_000L, 10_000)?.size).isEqualTo(10_000) }

        assertThat(asker.connectionsOpened.get()).isEqualTo(openedAtStart)
    }

    @Test
    fun `pieces asked for at once each arrive whole`() = runBlocking {
        connected()

        val pieces = (0 until 6).map { i -> async(Dispatchers.IO) { asker.read("video", i * 50_000L, 50_000) } }.awaitAll()

        pieces.forEachIndexed { i, piece ->
            assertThat(piece?.toList()).isEqualTo(file.copyOfRange(i * 50_000, (i + 1) * 50_000).toList())
        }
    }

    @Test
    fun `a piece at the file's end comes shorter than asked for`() {
        connected()

        val answer = asker.open("video", offset = 290_000, length = 50_000)

        assertThat(answer).isInstanceOf(PieceAnswer.Coming::class)
        answer as PieceAnswer.Coming
        assertThat(answer.length).isEqualTo(10_000L)
        assertThat(answer.input.use { it.readBytes() }.size).isEqualTo(10_000)
    }

    @Test
    fun `a piece the other phone won't send is refused, and the next one still comes`() {
        connected()
        openUntil = 100_000

        assertThat(asker.open("video", offset = 100_000, length = 10_000)).isEqualTo(PieceAnswer.Refused)
        assertThat(asker.open("other", offset = 0, length = 10_000)).isEqualTo(PieceAnswer.Refused)
        // Cut short where the closed part starts.
        assertThat(asker.read("video", offset = 95_000, length = 10_000)?.size).isEqualTo(5_000)
        assertThat(asker.isUp).isTrue()
    }

    @Test
    fun `a piece closed before its end doesn't spoil the next one`() {
        connected()

        val answer = asker.open("video", offset = 0, length = 200_000) as PieceAnswer.Coming
        answer.input.read(ByteArray(100))
        answer.input.close()

        assertThat(asker.read("video", offset = 5_000, length = 1_000)?.toList())
            .isEqualTo(file.copyOfRange(5_000, 6_000).toList())
    }

    @Test
    fun `only pieces that went whole are counted as served`() {
        connected()

        asker.read("video", offset = 0, length = 40_000)
        asker.read("video", offset = 40_000, length = 20_000)
        // The count is made after the last byte went: give the sharing side a moment.
        val until = System.currentTimeMillis() + 5_000
        while (served["video"] != 60_000L && System.currentTimeMillis() < until) Thread.sleep(10)

        assertThat(served["video"]).isEqualTo(60_000L)
    }

    @Test
    fun `a connection without the token reads nothing`() {
        val offer = sharer.listen("127.0.0.1")!!
        val stranger = LanPieceLink(scope, serve = { _, _, _ -> null }, log = {})

        stranger.connect(LinkFrame.PieceOffer(offer.address, offer.port, ByteArray(LanTokenBytes)))

        assertThat(stranger.open("video", offset = 0, length = 1_000)).isEqualTo(PieceAnswer.Broken)
        assertThat(stranger.isUp).isFalse()
        // The real phone still gets through afterwards.
        assertThat(asker.connect(offer)).isTrue()
        assertThat(asker.read("video", offset = 0, length = 1_000)?.size).isEqualTo(1_000)
        stranger.close()
    }

    @Test
    fun `without a listener to connect to, pieces go the other way`() {
        val offer = sharer.listen("127.0.0.1")!!
        sharer.close()

        assertThat(asker.connect(offer)).isFalse()
        assertThat(asker.isUp).isFalse()
        assertThat(asker.open("video", offset = 0, length = 1_000)).isEqualTo(PieceAnswer.Broken)
    }

    @Test
    fun `once the listener is gone, asking says so and stops going this way`() {
        connected()
        sharer.close()

        assertThat(asker.open("video", offset = 0, length = 1_000)).isEqualTo(PieceAnswer.Broken)
        assertThat(asker.isUp).isFalse()
    }

    @Test
    fun `a listener that moved is asked at its new place`() {
        connected()

        val moved = sharer.listen("127.0.0.1")!!
        assertThat(asker.connect(moved)).isTrue()

        assertThat(asker.read("video", offset = 0, length = 1_000)?.size).isEqualTo(1_000)
    }
}
