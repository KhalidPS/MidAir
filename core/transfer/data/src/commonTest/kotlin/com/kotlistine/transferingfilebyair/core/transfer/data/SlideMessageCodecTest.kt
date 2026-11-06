package com.kotlistine.transferingfilebyair.core.transfer.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.EdgeCrossing
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import kotlin.test.Test

class SlideMessageCodecTest {

    private val start = SlideMessage.DragStart(
        itemId = "7",
        name = "Q4 Report.pdf",
        mimeType = "application/pdf",
        sizeBytes = 16_000,
        cardWidthMm = 31.5f,
        cardHeightMm = 40.25f,
        thumbnail = byteArrayOf(1, 2, 3, -1),
    )
    private val move = SlideMessage.DragMove(
        itemId = "7",
        seq = 42,
        exitEdge = ExitEdge.LEFT,
        alongEdgeMm = 61.5f,
        penetrationMm = -3.25f,
        outwardMmPerS = 180f,
        alongEdgeMmPerS = -12f,
        tiltDegrees = 4.5f,
        atMillis = 1_793_000_000_123,
    )

    private fun roundTrip(message: SlideMessage): SlideMessage {
        val bytes = (SlideMessageCodec.encode(message) as Result.Success).data
        return (SlideMessageCodec.decode(bytes) as Result.Success).data
    }

    @Test
    fun `every message survives a round trip unchanged`() {
        val messages = listOf(
            start,
            start.copy(thumbnail = null),
            move,
            SlideMessage.DragCancel("7"),
            SlideMessage.DragMoves(
                listOf(
                    SlideMessage.DragMove("7", 3, ExitEdge.RIGHT, 12f, -4f, 300f, 0f, 2f, atMillis = 1_700_000_000_000),
                    SlideMessage.DragMove("7", 4, ExitEdge.RIGHT, 12.5f, -2f, 310f, 1f, 2f, atMillis = 1_700_000_000_016),
                ),
            ),
            SlideMessage.DragCommit("7", outwardMmPerS = 300f, alongEdgeMmPerS = 5f),
            SlideMessage.FileOffer("7", transferId = -4_611_686_018_427_387_904),
            SlideMessage.FileQueued("7"),
            SlideMessage.FileCancelled("7", bySender = true),
            SlideMessage.FileCancelled("7", bySender = false),
            SlideMessage.Ping(sentAtMillis = 1_700_000_000_000),
            SlideMessage.Pong(pingSentAtMillis = 1_700_000_000_000),
            SlideMessage.Pong(pingSentAtMillis = 1_700_000_000_000, answeredAtMillis = 1_700_000_000_321),
            SlideMessage.SwipeCrossed(
                EdgeCrossing(
                    leaving = true,
                    edge = ExitEdge.RIGHT,
                    alongEdgeMm = 52.5f,
                    atMillis = 1_700_000_000_500,
                    acrossMmPerS = 240f,
                    alongEdgeMmPerS = -6f,
                ),
            ),
            SlideMessage.PlaybackOffer("7", sizeBytes = 24_000_000_000, positionMs = 61_250, isPlaying = true),
            SlideMessage.PlaybackOffer("7", 24_000, 0, isPlaying = false, httpUrl = "http://192.168.1.20:40123/v/7?t=ab"),
            SlideMessage.PlaybackClock("7", positionMs = 61_500, isPlaying = false),
            SlideMessage.Grabbed(itemId = "7"),
            SlideMessage.Grabbed(itemId = "7", handSize = 0.42f),
            SlideMessage.Released(itemId = "7"),
        ) + SlideMessage.FileRoute.Route.entries.map { SlideMessage.FileRoute("7", it) }

        messages.forEach { assertThat(roundTrip(it)).isEqualTo(it) }
    }

    @Test
    fun `a move is small enough to stream every frame`() {
        val bytes = (SlideMessageCodec.encode(move) as Result.Success).data

        assertThat(bytes.size).isLessThan(80)
    }

    @Test
    fun `a move from an older build, without a timestamp, reads as untimed`() {
        // An older build has no timestamp field, which is what an unknown (default) one encodes to.
        val untimed = (SlideMessageCodec.encode(move.copy(atMillis = 0L)) as Result.Success).data
        val timed = (SlideMessageCodec.encode(move) as Result.Success).data

        assertThat(untimed.size).isLessThan(timed.size)
        assertThat(SlideMessageCodec.decode(untimed)).isEqualTo(Result.Success(move.copy(atMillis = 0L)))
    }

    @Test
    fun `a playback clock is small enough to send several times a second`() {
        val bytes = (SlideMessageCodec.encode(SlideMessage.PlaybackClock("7", 3_600_000, true)) as Result.Success).data

        assertThat(bytes.size).isLessThan(40)
    }

    @Test
    fun `a message over the transport limit is refused`() {
        val huge = start.copy(thumbnail = ByteArray(SlideMessageCodec.MaxMessageBytes))

        assertThat(SlideMessageCodec.encode(huge)).isEqualTo(Result.Failure(DataError.Network.PAYLOAD_TOO_LARGE))
    }

    @Test
    fun `messages with impossible values are rejected as malformed`() {
        val impossible = listOf(
            start.copy(cardWidthMm = Float.NaN),
            start.copy(cardHeightMm = -4f),
            start.copy(cardWidthMm = 1e9f),
            start.copy(sizeBytes = -1),
            move.copy(penetrationMm = Float.POSITIVE_INFINITY),
            move.copy(alongEdgeMm = 50_000f),
            move.copy(tiltDegrees = Float.NaN),
            move.copy(atMillis = -1),
            SlideMessage.DragCommit("7", outwardMmPerS = Float.NaN, alongEdgeMmPerS = 0f),
            SlideMessage.PlaybackOffer("7", sizeBytes = 0, positionMs = 0, isPlaying = true),
            SlideMessage.PlaybackOffer("7", sizeBytes = 1_000, positionMs = -5, isPlaying = true),
            SlideMessage.PlaybackClock("7", positionMs = -1, isPlaying = true),
            SlideMessage.PlaybackOffer("7", sizeBytes = 1_000, positionMs = 0, isPlaying = true, httpUrl = "file:///sdcard/x"),
            SlideMessage.Grabbed("7", handSize = Float.NaN),
            SlideMessage.Grabbed("7", handSize = 1.5f),
        )

        impossible.forEach { message ->
            val bytes = (SlideMessageCodec.encode(message) as Result.Success).data
            assertThat(SlideMessageCodec.decode(bytes)).isEqualTo(Result.Failure(DataError.Network.MALFORMED_MESSAGE))
        }
    }

    @Test
    fun `bytes that are not a message are reported as malformed`() {
        val garbage = byteArrayOf(0x7f, 0x00, 0x13, 0x37)

        assertThat(SlideMessageCodec.decode(garbage)).isInstanceOf(Result.Failure::class)
        assertThat(SlideMessageCodec.decode(garbage))
            .isEqualTo(Result.Failure(DataError.Network.MALFORMED_MESSAGE))
    }
}
