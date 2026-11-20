package com.kotlistine.midair.core.media

import com.kotlistine.midair.core.domain.util.ByteStream
import com.kotlistine.midair.core.domain.util.Result
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

/** A jump forward shorter than this reads on through the open stream instead of opening one at the new place. */
private const val ReadThroughBytes = 256 * 1024

/**
 * A file on the other device ([source]) as an InputStream FFmpeg can seek: JavaCV seeks one by going back to its
 * [mark] and [skip]ping forward, and finds its size by skipping to the end. Here both only move a position; bytes are
 * read from the other device only when FFmpeg reads, through a stream opened at that position (and kept while
 * reading goes on from where it is).
 *
 * Read from FFmpeg's reader thread only (it blocks while the bytes come).
 */
internal class RemoteInputStream(private val source: VideoSource.Remote) : InputStream() {
    private var position = 0L
    private var marked = 0L
    private var stream: ByteStream? = null
    private var streamAt = 0L
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 0xFF

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val left = source.sizeBytes - position
        if (left <= 0) return -1
        val open = streamAtPosition()
        val read = open.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (read < 0) return -1
        position += read
        streamAt += read
        return read
    }

    private fun streamAtPosition(): ByteStream {
        stream?.let { open ->
            if (streamAt == position) return open
            val gap = position - streamAt
            if (gap in 1..ReadThroughBytes && readThrough(open, gap)) return open
            open.close()
            stream = null
        }
        val opened = runBlocking { source.open(position) }
        val open = (opened as? Result.Success)?.data ?: throw IOException("The video can't be read from the other device")
        stream = open
        streamAt = position
        return open
    }

    /** Reads [count] bytes of [open] and drops them; false when it ends first. */
    private fun readThrough(open: ByteStream, count: Long): Boolean {
        val scratch = ByteArray(minOf(count, 64L * 1024).toInt())
        var left = count
        while (left > 0) {
            val read = open.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (read < 0) return false
            left -= read
            streamAt += read
        }
        return true
    }

    override fun skip(count: Long): Long {
        if (count <= 0) return 0
        val skipped = minOf(count, source.sizeBytes - position).coerceAtLeast(0)
        position += skipped
        return skipped
    }

    override fun available(): Int = 0

    override fun markSupported(): Boolean = true

    override fun mark(readLimit: Int) {
        marked = position
    }

    override fun reset() {
        position = marked
    }

    override fun close() {
        stream?.close()
        stream = null
    }
}
