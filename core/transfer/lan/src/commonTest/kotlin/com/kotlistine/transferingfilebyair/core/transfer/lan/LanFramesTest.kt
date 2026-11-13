package com.kotlistine.transferingfilebyair.core.transfer.lan

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import kotlin.test.Test

class LanFramesTest {

    @Test
    fun `every frame reads back as what was written`() {
        val frames = listOf(
            LanFrame.Accept,
            LanFrame.Decline,
            LanFrame.Forget,
            LanFrame.Heartbeat,
            LanFrame.FileStart(transferId = 42, sizeBytes = 1_000_000),
            LanFrame.FileEnd(transferId = 42),
            LanFrame.FileDone(transferId = 42),
            LanFrame.FileCancel(transferId = Long.MAX_VALUE),
            LanFrame.Placement(side = ExitEdge.LEFT, verticalOffsetMm = -42.5f),
            LanFrame.Placement(side = ExitEdge.RIGHT, verticalOffsetMm = 0f),
            LanFrame.MediaRead(requestId = -5, fileId = "item-12", offset = 3_000_000_000, length = 262_144, forKeeps = true),
            LanFrame.MediaRead(requestId = 6, fileId = "", offset = 0, length = 1, forKeeps = false),
            LanFrame.MediaDone(requestId = 9, ok = true),
            LanFrame.MediaDone(requestId = 9, ok = false),
            LanFrame.MediaCancel(requestId = Long.MIN_VALUE),
        )
        frames.forEach { frame -> assertThat(decodeLanFrame(frame.encode())).isEqualTo(frame) }
    }

    @Test
    fun `frames with bytes keep their bytes`() {
        val chunk = decodeLanFrame(LanFrame.FileChunk(7, byteArrayOf(1, 2, 3)).encode())
        assertThat(chunk).isNotNull().isInstanceOf<LanFrame.FileChunk>()
        chunk as LanFrame.FileChunk
        assertThat(chunk.transferId).isEqualTo(7L)
        assertThat(chunk.bytes.toList()).isEqualTo(listOf<Byte>(1, 2, 3))

        val auth = decodeLanFrame(LanFrame.Auth(AuthMode.TRUSTED, byteArrayOf(9)).encode()) as LanFrame.Auth
        assertThat(auth.mode).isEqualTo(AuthMode.TRUSTED)
        assertThat(auth.mac.toList()).isEqualTo(listOf<Byte>(9))

        val data = decodeLanFrame(LanFrame.MediaData(-3, byteArrayOf(4, 5)).encode()) as LanFrame.MediaData
        assertThat(data.requestId).isEqualTo(-3L)
        assertThat(data.bytes.toList()).isEqualTo(listOf<Byte>(4, 5))
    }

    @Test
    fun `bytes that are not exactly one frame are not read as one`() {
        assertThat(decodeLanFrame(byteArrayOf())).isNull()
        assertThat(decodeLanFrame(byteArrayOf(99))).isNull()
        assertThat(decodeLanFrame(LanFrame.Accept.encode() + 0)).isNull()
        // A chunk claiming more bytes than it carries.
        val truncated = LanFrame.FileChunk(1, ByteArray(10)).encode().dropLast(3).toByteArray()
        assertThat(decodeLanFrame(truncated)).isNull()
    }

    @Test
    fun `a read of nothing, or from before the start, is not read`() {
        assertThat(decodeLanFrame(LanFrame.MediaRead(1, "a", offset = 0, length = 0, forKeeps = false).encode())).isNull()
        assertThat(decodeLanFrame(LanFrame.MediaRead(1, "a", offset = -1, length = 10, forKeeps = false).encode())).isNull()
    }

    @Test
    fun `a placement with no such side or an impossible offset is not read`() {
        val noSuchSide = ByteWriter().apply {
            byte(13)
            byte(7)
            int(0f.toRawBits())
        }
        assertThat(decodeLanFrame(noSuchSide.toByteArray())).isNull()
        val notANumber = ByteWriter().apply {
            byte(13)
            byte(0)
            int(Float.NaN.toRawBits())
        }
        assertThat(decodeLanFrame(notANumber.toByteArray())).isNull()
    }

    @Test
    fun `a file can't claim a negative size`() {
        val writer = ByteWriter().apply {
            byte(8)
            long(1)
            long(-5)
        }
        assertThat(decodeLanFrame(writer.toByteArray())).isNull()
    }

    @Test
    fun `a hello reads back, and one with a short id does not`() {
        val hello = LanHello(
            version = LanProtocolVersion,
            purpose = Purpose.FILES,
            deviceId = ByteArray(DeviceIdBytes) { it.toByte() },
            name = "Khalid's laptop",
            publicKey = ByteArray(91) { 3 },
            nonce = ByteArray(NonceBytes) { 5 },
        )
        val read = LanHello.decode(hello.encode())
        assertThat(read).isNotNull()
        assertThat(read!!.name).isEqualTo("Khalid's laptop")
        assertThat(read.purpose).isEqualTo(Purpose.FILES)

        val shortId = LanHello(1, Purpose.CONTROL, ByteArray(4), "x", ByteArray(1), ByteArray(NonceBytes))
        assertThat(LanHello.decode(shortId.encode())).isNull()
    }
}
