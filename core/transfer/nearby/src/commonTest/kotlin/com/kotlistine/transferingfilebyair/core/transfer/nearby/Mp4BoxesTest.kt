package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

class Mp4BoxesTest {

    /** A box's header: its length in four bytes, then its name. */
    private fun header(size: Long, type: String): ByteArray =
        ByteArray(4) { (size shr (8 * (3 - it))).toByte() } + type.encodeToByteArray()

    /** The header of a box longer than 4 GB: a 1, its name, then its length in eight bytes. */
    private fun longHeader(size: Long, type: String): ByteArray =
        header(1, type) + ByteArray(8) { (size shr (8 * (7 - it))).toByte() }

    /** A file as a row of boxes: reading at a box's start gives its header, anywhere else nothing. */
    private fun file(vararg boxes: Pair<String, Long>): (Long) -> ByteArray? {
        val headers = mutableMapOf<Long, ByteArray>()
        var offset = 0L
        for ((type, size) in boxes) {
            headers[offset] = header(size, type)
            offset += size
        }
        return { headers[it] }
    }

    @Test
    fun `a box says what it is, where it is and how long`() {
        assertThat(mp4BoxAt(offset = 100, header = header(500, "moov"), fileSize = 1_000))
            .isEqualTo(Mp4Box("moov", start = 100, headerBytes = 8, size = 500))
    }

    @Test
    fun `a box longer than 4 GB has its length after its name`() {
        val box = mp4BoxAt(offset = 32, header = longHeader(5_000_000_000, "mdat"), fileSize = 6_000_000_000)

        assertThat(box).isEqualTo(Mp4Box("mdat", start = 32, headerBytes = 16, size = 5_000_000_000))
        assertThat(box).isNotNull().transform { it.body }.isEqualTo(ByteSpan(48, 5_000_000_032))
    }

    @Test
    fun `a box of length zero runs to the end of the file`() {
        assertThat(mp4BoxAt(offset = 40, header = header(0, "mdat"), fileSize = 1_000))
            .isEqualTo(Mp4Box("mdat", start = 40, headerBytes = 8, size = 960))
    }

    @Test
    fun `bytes that reach past the end of the file are no box`() {
        assertThat(mp4BoxAt(offset = 600, header = header(500, "moov"), fileSize = 1_000)).isNull()
    }

    @Test
    fun `a name that is not four printable characters is no box`() {
        assertThat(mp4BoxAt(offset = 0, header = header(500, "mo\u0001v"), fileSize = 1_000)).isNull()
    }

    @Test
    fun `a length shorter than its own header is no box`() {
        assertThat(mp4BoxAt(offset = 0, header = header(4, "free"), fileSize = 1_000)).isNull()
    }

    @Test
    fun `too few bytes are no box`() {
        assertThat(mp4BoxAt(offset = 0, header = ByteArray(5), fileSize = 1_000)).isNull()
    }

    @Test
    fun `the pictures and sound of a phone recording lie between its header and its index`() {
        val recording = file("ftyp" to 32, "uuid" to 368, "mdat" to 9_000, "moov" to 600)

        assertThat(mp4MediaSpans(fileSize = 10_000, headerAt = recording)).isEqualTo(listOf(ByteSpan(408, 9_400)))
    }

    @Test
    fun `a video with its index first has its pictures and sound at the end`() {
        val web = file("ftyp" to 32, "moov" to 600, "mdat" to 9_368)

        assertThat(mp4MediaSpans(fileSize = 10_000, headerAt = web)).isEqualTo(listOf(ByteSpan(640, 10_000)))
    }

    @Test
    fun `several boxes of pictures and sound are all found`() {
        val parts = file("ftyp" to 32, "mdat" to 100, "moov" to 68, "mdat" to 200)

        assertThat(mp4MediaSpans(fileSize = 400, headerAt = parts))
            .isNotNull().containsExactly(ByteSpan(40, 132), ByteSpan(208, 400))
    }

    @Test
    fun `a video with no pictures and sound has none to keep closed`() {
        assertThat(mp4MediaSpans(fileSize = 632, headerAt = file("ftyp" to 32, "moov" to 600))).isNotNull().isEmpty()
    }

    @Test
    fun `a file that does not start with a box is not known to be MP4`() {
        assertThat(mp4MediaSpans(fileSize = 10_000) { ByteArray(16) }).isNull()
    }

    @Test
    fun `a file that cannot be read is not known to be MP4`() {
        assertThat(mp4MediaSpans(fileSize = 10_000) { null }).isNull()
    }

    @Test
    fun `a file whose boxes stop making sense half way is not known to be MP4`() {
        val broken = file("ftyp" to 32, "mdat" to 500)

        assertThat(mp4MediaSpans(fileSize = 10_000, headerAt = broken)).isNull()
    }

    @Test
    fun `an empty file is not known to be MP4`() {
        assertThat(mp4MediaSpans(fileSize = 0) { null }).isNull()
    }

    @Test
    fun `what is asked for is cut where a closed part starts`() {
        assertThat(openSpan(offset = 0, length = 1_000, closed = listOf(ByteSpan(408, 9_400))))
            .isEqualTo(ByteSpan(0, 408))
    }

    @Test
    fun `nothing may be read from inside a closed part`() {
        assertThat(openSpan(offset = 408, length = 1_000, closed = listOf(ByteSpan(408, 9_400)))).isNull()
        assertThat(openSpan(offset = 9_399, length = 1_000, closed = listOf(ByteSpan(408, 9_400)))).isNull()
    }

    @Test
    fun `past the closed parts everything asked for may be read`() {
        assertThat(openSpan(offset = 9_400, length = 600, closed = listOf(ByteSpan(408, 9_400))))
            .isEqualTo(ByteSpan(9_400, 10_000))
    }

    @Test
    fun `with nothing closed everything asked for may be read`() {
        assertThat(openSpan(offset = 50, length = 100, closed = emptyList())).isEqualTo(ByteSpan(50, 150))
    }

    @Test
    fun `asking for no bytes gives none`() {
        assertThat(openSpan(offset = 50, length = 0, closed = emptyList())).isNull()
    }
}
