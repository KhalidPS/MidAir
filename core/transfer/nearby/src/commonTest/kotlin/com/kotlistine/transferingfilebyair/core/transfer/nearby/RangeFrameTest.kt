package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class RangeFrameTest {

    private val request = LinkFrame.RangeRequest(requestId = 7, offset = 0, length = 4_194_304, fileId = "item-42")

    @Test
    fun `range frames survive a round trip`() {
        val frames = listOf(
            LinkFrame.RangeRequest(requestId = 7, offset = 24_000_000_123, length = 4_194_304, fileId = "item-42"),
            LinkFrame.RangeRequest(requestId = Long.MAX_VALUE, offset = 0, length = 1, fileId = "ünïcode"),
            LinkFrame.RangeOpened(requestId = 7, payloadId = -4_611_686_018_427_387_904),
            LinkFrame.RangeRefused(requestId = 1),
            LinkFrame.DigestRequest(requestId = 8, fileId = "item-42"),
            LinkFrame.Digest(requestId = 8, sha256 = ByteArray(DigestBytes) { it.toByte() }),
        )

        frames.forEach { assertThat(decodeLinkFrame(it.encode())).isEqualTo(it) }
    }

    @Test
    fun `a range request without a file, or with a negative offset, is dropped`() {
        val noFile = request.copy(fileId = "x").encode().dropLast(1).toByteArray()
        val negative = request.copy(offset = -1).encode()

        assertThat(decodeLinkFrame(noFile)).isNull()
        assertThat(decodeLinkFrame(negative)).isNull()
    }

    @Test
    fun `a range request for nothing, or for too much at once, is dropped`() {
        assertThat(decodeLinkFrame(request.copy(length = 0).encode())).isNull()
        assertThat(decodeLinkFrame(request.copy(length = MaxRangeBytes + 1).encode())).isNull()
    }

    @Test
    fun `a range request with an absurdly long file id is dropped`() {
        val long = request.copy(fileId = "x".repeat(MaxFileIdBytes + 1)).encode()

        assertThat(decodeLinkFrame(long)).isNull()
    }

    @Test
    fun `a digest request without a file, and a digest of the wrong length, are dropped`() {
        val noFile = LinkFrame.DigestRequest(requestId = 8, fileId = "x").encode().dropLast(1).toByteArray()
        val short = LinkFrame.Digest(requestId = 8, sha256 = ByteArray(DigestBytes - 1)).encode()
        val long = LinkFrame.Digest(requestId = 8, sha256 = ByteArray(DigestBytes + 1)).encode()

        assertThat(decodeLinkFrame(noFile)).isNull()
        assertThat(decodeLinkFrame(short)).isNull()
        assertThat(decodeLinkFrame(long)).isNull()
    }

    @Test
    fun `range answers of the wrong length are dropped`() {
        val opened = LinkFrame.RangeOpened(requestId = 7, payloadId = 9).encode()
        val refused = LinkFrame.RangeRefused(requestId = 7).encode()

        assertThat(decodeLinkFrame(opened + 0.toByte())).isNull()
        assertThat(decodeLinkFrame(refused.copyOf(refused.size - 1))).isNull()
    }
}
