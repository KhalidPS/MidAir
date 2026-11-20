package com.kotlistine.midair.core.domain.util

/**
 * Bytes arriving in order, read with blocking calls: call it from a background thread (a player's loader thread),
 * never the main one.
 */
interface ByteStream {
    /**
     * Reads up to [length] bytes into [buffer] from [offset], waiting until at least one is there. Returns how many
     * were read, or -1 at the end. Throws when the stream breaks (the link dropped, or it was closed meanwhile).
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Stops the stream; the other end stops sending. */
    fun close()
}
