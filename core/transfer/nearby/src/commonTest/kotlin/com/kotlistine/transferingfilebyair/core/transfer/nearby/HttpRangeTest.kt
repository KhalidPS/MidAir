package com.kotlistine.transferingfilebyair.core.transfer.nearby

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class HttpRangeTest {

    @Test
    fun `no range header asks for the whole file`() {
        assertThat(parseByteRange(null, size = 1_000)).isEqualTo(ByteRangeRequest.Whole)
    }

    @Test
    fun `an open range runs to the end of the file`() {
        assertThat(parseByteRange("bytes=400-", size = 1_000)).isEqualTo(ByteRangeRequest.Part(400, 999))
    }

    @Test
    fun `a closed range is read as asked, and cut at the end of the file`() {
        assertThat(parseByteRange("bytes=0-99", size = 1_000)).isEqualTo(ByteRangeRequest.Part(0, 99))
        assertThat(parseByteRange("bytes=900-5000", size = 1_000)).isEqualTo(ByteRangeRequest.Part(900, 999))
    }

    @Test
    fun `a suffix range is the end of the file`() {
        assertThat(parseByteRange("bytes=-100", size = 1_000)).isEqualTo(ByteRangeRequest.Part(900, 999))
        assertThat(parseByteRange("bytes=-5000", size = 1_000)).isEqualTo(ByteRangeRequest.Part(0, 999))
    }

    @Test
    fun `a range outside the file, backwards, or malformed can't be served`() {
        val headers = listOf("bytes=1000-", "bytes=500-100", "bytes=abc-", "items=0-10", "bytes=0-10,20-30", "bytes=-0")

        headers.forEach { assertThat(parseByteRange(it, size = 1_000)).isEqualTo(ByteRangeRequest.Unsatisfiable) }
    }

    @Test
    fun `a part knows how many bytes it holds`() {
        assertThat(ByteRangeRequest.Part(100, 199).length).isEqualTo(100L)
    }
}
