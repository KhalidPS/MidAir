package com.kotlistine.transferingfilebyair.core.transfer.lan

import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Asked for at a time: small enough that a piece already on its way when a card lifts is through in a blink. */
private const val PieceBytes = 256 * 1024

/** Asked for ahead of what is being read: the next pieces are on their way while this one is used. */
private const val PiecesAhead = 2

/** Without a byte of a piece asked for for this long, the read fails (the other end may be an older build). */
private const val PieceTimeoutMillis = 10_000L

private const val DataChunkBytes = 64 * 1024

/** While a card is in the air, a pull looks this often whether it may ask for its next piece. */
private const val HeldPollMillis = 20L

/**
 * Reads files on the other device over the link's files' connection, a piece at a time: what plays a video arriving
 * on a card from the frame it is at, and pulls a file whole once its card lands (holding back while a card is in the
 * air, so the cards' moves never wait behind a file's bytes).
 *
 * Each end serves only what it [share]d. The reader asks ([LanFrame.MediaRead]), a few pieces ahead; the other end
 * answers each in chunks and says when it is done. Nothing is sent that wasn't asked for, so a reader that stops
 * reading stops the bytes.
 *
 * On the LAN the link is always fast: it never runs on Bluetooth.
 */
internal class LanMediaChannel(
    private val files: LanFiles,
    private val scope: CoroutineScope,
    /** The live link's files' connection (waited for a moment while it opens); null with no link. */
    private val filesChannel: suspend () -> SecureChannel?,
) : MediaChannel {

    override val isFast: Boolean = true

    // Serving.
    private val shared = ConcurrentHashMap<String, String>()
    private val served = ConcurrentHashMap<String, AtomicLong>()
    private val serving = ConcurrentHashMap<Long, Job>()

    // Reading.
    private val pieces = ConcurrentHashMap<Long, Piece>()
    private val settled = ConcurrentHashMap<String, String>()

    @Volatile
    private var isHeld = false

    override fun share(fileId: String, uri: String): String? {
        shared[fileId] = uri
        served.remove(fileId)
        // No address on a shared network: the link itself is the fast way.
        return null
    }

    override fun shareIndex(fileId: String, uri: String, sizeBytes: Long) {
        shared.putIfAbsent(fileId, uri)
    }

    override fun unshare(fileId: String) {
        shared.remove(fileId)
    }

    override suspend fun canReach(url: String): Boolean = false

    override fun servedBytes(fileId: String): Long = served[fileId]?.get() ?: 0L

    override fun holdFetches(hold: Boolean) {
        isHeld = hold
    }

    override fun settle(fileId: String, uri: String) {
        settled[fileId] = uri
    }

    override fun forget(fileId: String) {
        settled.remove(fileId)
    }

    override suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network> {
        settled[fileId]?.let { uri ->
            val local = withContext(Dispatchers.IO) { openLocal(uri, offset) }
            if (local != null) return Result.Success(local)
        }
        val channel = filesChannel() ?: return Result.Failure(DataError.Network.DISCONNECTED)
        return Result.Success(MediaStream(channel, fileId, offset))
    }

    override suspend fun fetch(
        fileId: String,
        sizeBytes: Long,
        onProgress: (Float) -> Unit,
    ): Result<String, DataError> = withContext(Dispatchers.IO) {
        val channel = filesChannel() ?: return@withContext Result.Failure(DataError.Network.DISCONNECTED)
        val target = try {
            files.createIncoming(Random.nextLong(1, Long.MAX_VALUE))
        } catch (_: IOException) {
            return@withContext Result.Failure(DataError.Local.DISK_FULL)
        }
        val reader = PieceReader(channel, fileId, offset = 0L, forKeeps = true)
        try {
            val buffer = ByteArray(DataChunkBytes)
            var received = 0L
            while (true) {
                ensureActive()
                val read = runInterruptible { reader.read(buffer, 0, buffer.size) }
                if (read < 0) break
                target.stream.write(buffer, 0, read)
                received += read
                if (sizeBytes > 0) onProgress((received.toDouble() / sizeBytes).toFloat().coerceAtMost(1f))
                if (received > sizeBytes) break
            }
            target.stream.close()
            if (received != sizeBytes) {
                target.discard()
                Result.Failure(DataError.Network.UNKNOWN)
            } else {
                Result.Success(target.location)
            }
        } catch (_: IOException) {
            target.discard()
            Result.Failure(DataError.Network.DISCONNECTED)
        } catch (e: CancellationException) {
            target.discard()
            throw e
        } finally {
            reader.close()
        }
    }

    /** A frame about reading files, from the files' connection [channel]. */
    fun onFrame(channel: SecureChannel, frame: LanFrame) {
        when (frame) {
            is LanFrame.MediaRead -> serve(channel, frame)
            is LanFrame.MediaCancel -> serving.remove(frame.requestId)?.cancel()
            is LanFrame.MediaData -> pieces[frame.requestId]?.arrive(frame.bytes)
            is LanFrame.MediaDone -> pieces.remove(frame.requestId)?.finish(frame.ok)
            else -> Unit
        }
    }

    /** The link ended: what is being read from it fails, and nothing more is served on it. */
    fun onLinkDropped() {
        pieces.values.forEach { it.finish(ok = false) }
        pieces.clear()
        serving.values.forEach(Job::cancel)
        serving.clear()
    }

    private fun serve(channel: SecureChannel, read: LanFrame.MediaRead) {
        val uri = shared[read.fileId]
        if (uri == null) {
            channel.sendQuietly(LanFrame.MediaDone(read.requestId, ok = false))
            return
        }
        serving[read.requestId] = scope.launch(Dispatchers.IO) {
            var ok = false
            try {
                val opened = files.open(uri) ?: return@launch
                opened.stream.use { stream ->
                    if (stream.skipFully(read.offset)) {
                        val buffer = ByteArray(minOf(DataChunkBytes, read.length))
                        var left = read.length
                        while (left > 0 && isActive) {
                            val count = stream.read(buffer, 0, minOf(buffer.size, left))
                            if (count < 0) break
                            channel.send(LanFrame.MediaData(read.requestId, buffer.copyOf(count)))
                            left -= count
                            if (read.forKeeps) served.getOrPut(read.fileId) { AtomicLong() }.addAndGet(count.toLong())
                        }
                    }
                }
                ok = isActive
            } catch (_: IOException) {
                // Said below: the reader fails this piece.
            } finally {
                serving.remove(read.requestId)
                // A piece the reader called off needs no answer.
                if (isActive) channel.sendQuietly(LanFrame.MediaDone(read.requestId, ok))
            }
        }
    }

    private fun openLocal(uri: String, offset: Long): ByteStream? {
        val opened = files.open(uri) ?: return null
        if (!opened.stream.skipFully(offset)) {
            runCatching { opened.stream.close() }
            return null
        }
        return InputByteStream(opened.stream)
    }

    /**
     * A file read from [offset] on: over the link, until the file is [settle]d on this device, then from there (on
     * from the same byte, so a video playing from it doesn't notice).
     */
    private inner class MediaStream(channel: SecureChannel, private val fileId: String, offset: Long) : ByteStream {
        private var position = offset
        private var remote: PieceReader? = PieceReader(channel, fileId, offset, forKeeps = false)
        private var local: ByteStream? = null

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (local == null) {
                settled[fileId]?.let { uri ->
                    openLocal(uri, position)?.let { opened ->
                        remote?.close()
                        remote = null
                        local = opened
                    }
                }
            }
            val source = local ?: remote ?: throw IOException("Closed")
            val read = source.read(buffer, offset, length)
            if (read > 0) position += read
            return read
        }

        override fun close() {
            remote?.close()
            local?.close()
        }
    }

    /**
     * Reads the other end's file [fileId] from [offset] to its end, asking for it [PiecesAhead] pieces ahead. A pull
     * ([forKeeps]) asks for no new piece while a card is in the air. Blocks; [close] stops it.
     */
    private inner class PieceReader(
        private val channel: SecureChannel,
        private val fileId: String,
        offset: Long,
        private val forKeeps: Boolean,
    ) : ByteStream {
        private val outstanding = ArrayDeque<Piece>()
        private var nextOffset = offset
        private var reachedEnd = false
        private var current: ByteArray? = null
        private var currentAt = 0

        @Volatile
        private var isClosed = false

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (isClosed) throw IOException("Closed")
                current?.let { chunk ->
                    val count = minOf(length, chunk.size - currentAt)
                    chunk.copyInto(buffer, offset, currentAt, currentAt + count)
                    currentAt += count
                    if (currentAt == chunk.size) current = null
                    return count
                }
                askAhead()
                val piece = outstanding.firstOrNull()
                if (piece == null) {
                    if (reachedEnd) return -1
                    // Held: a card is in the air.
                    Thread.sleep(HeldPollMillis)
                    continue
                }
                when (val next = piece.next(PieceTimeoutMillis)) {
                    null -> throw IOException("No answer for a piece of $fileId")
                    is ByteArray -> {
                        current = next
                        currentAt = 0
                    }
                    is PieceEnd -> {
                        if (!next.ok) throw IOException("$fileId can't be read")
                        outstanding.removeFirst()
                        // Fewer bytes than asked: the file ended there; what was asked after it is empty.
                        if (piece.received < piece.length) {
                            reachedEnd = true
                            outstanding.forEach(::callOff)
                            outstanding.clear()
                        }
                    }
                }
            }
        }

        private fun askAhead() {
            while (!reachedEnd && outstanding.size < PiecesAhead && !(forKeeps && isHeld)) {
                val piece = Piece(Random.nextLong(), PieceBytes)
                pieces[piece.requestId] = piece
                outstanding.addLast(piece)
                channel.send(LanFrame.MediaRead(piece.requestId, fileId, nextOffset, PieceBytes, forKeeps))
                nextOffset += PieceBytes
            }
        }

        private fun callOff(piece: Piece) {
            pieces.remove(piece.requestId)
            channel.sendQuietly(LanFrame.MediaCancel(piece.requestId))
        }

        override fun close() {
            if (isClosed) return
            isClosed = true
            outstanding.forEach(::callOff)
            outstanding.clear()
        }
    }

    /** One piece asked for: its bytes as they come, then how it ended. */
    private class Piece(val requestId: Long, val length: Int) {
        private val arrivals = LinkedBlockingQueue<Any>()

        @Volatile
        var received = 0
            private set

        fun arrive(bytes: ByteArray) {
            arrivals.put(bytes)
        }

        fun finish(ok: Boolean) {
            arrivals.put(PieceEnd(ok))
        }

        /** The next bytes, or how it ended; null after [timeoutMillis] with nothing. */
        fun next(timeoutMillis: Long): Any? {
            val next = arrivals.poll(timeoutMillis, TimeUnit.MILLISECONDS)
            if (next is ByteArray) received += next.size
            return next
        }
    }

    private class PieceEnd(val ok: Boolean)
}

/** A local file's stream as a [ByteStream]. */
private class InputByteStream(private val stream: InputStream) : ByteStream {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length)

    override fun close() {
        runCatching { stream.close() }
    }
}

/** Skips [count] bytes; false when the stream ends first. */
private fun InputStream.skipFully(count: Long): Boolean {
    var left = count
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            if (read() < 0) return false
            left--
        }
    }
    return true
}

/** Sends [frame], or nothing when the connection is gone (its end is noticed elsewhere). */
private fun SecureChannel.sendQuietly(frame: LanFrame) {
    try {
        send(frame)
    } catch (_: IOException) {
        // The link is ending.
    }
}
