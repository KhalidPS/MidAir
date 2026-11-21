package com.kotlistine.midair.core.media

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.kotlistine.midair.core.domain.util.ByteStream
import com.kotlistine.midair.core.domain.util.Result
import kotlin.random.Random
import kotlin.test.Test

class RemoteInputStreamTest {
    private val bytes = Random.nextBytes(1_000_000)
    private val opens = mutableListOf<Long>()
    private val stream = RemoteInputStream(
        VideoSource.Remote("video", bytes.size.toLong()) { offset ->
            opens += offset
            Result.Success(ArrayByteStream(bytes, offset.toInt()))
        },
    )

    private fun readFully(count: Int): List<Byte> {
        val out = ByteArray(count)
        var at = 0
        while (at < count) at += stream.read(out, at, count - at)
        return out.toList()
    }

    @Test
    fun `nothing is read from the other device until something is read here`() {
        stream.mark(Int.MAX_VALUE)
        // How JavaCV finds the size: skipping to the end, and back.
        assertThat(stream.skip(Long.MAX_VALUE)).isEqualTo(1_000_000L)
        assertThat(stream.skip(Long.MAX_VALUE)).isEqualTo(0L)
        stream.reset()
        assertThat(opens).isEqualTo(emptyList<Long>())
        assertThat(readFully(10)).isEqualTo(bytes.take(10))
        assertThat(opens).containsExactly(0L)
    }

    @Test
    fun `a jump far ahead opens the file there, a short one reads on`() {
        readFully(100)
        stream.skip(50_000)
        assertThat(readFully(10)).isEqualTo(bytes.toList().subList(50_100, 50_110))
        assertThat(opens).containsExactly(0L)

        stream.mark(Int.MAX_VALUE)
        stream.reset()
        stream.skip(800_000)
        assertThat(readFully(10)).isEqualTo(bytes.toList().subList(850_110, 850_120))
        assertThat(opens).containsExactly(0L, 850_110L)
    }

    @Test
    fun `going back to the mark reads from there again`() {
        stream.mark(Int.MAX_VALUE)
        readFully(500)
        stream.reset()
        assertThat(readFully(5)).isEqualTo(bytes.take(5))
        assertThat(opens).containsExactly(0L, 0L)
    }

    @Test
    fun `the file ends where its size says`() {
        stream.skip(999_998)
        val out = ByteArray(10)
        assertThat(stream.read(out, 0, 10)).isEqualTo(2)
        assertThat(stream.read(out, 0, 10)).isEqualTo(-1)
    }
}

/** [bytes] from [start], read in small pieces as a link would hand them over. */
internal class ArrayByteStream(private val bytes: ByteArray, start: Int) : ByteStream {
    private var at = start

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (at >= bytes.size) return -1
        val count = minOf(length, 4_096, bytes.size - at)
        bytes.copyInto(buffer, offset, at, at + count)
        at += count
        return count
    }

    override fun close() = Unit
}
