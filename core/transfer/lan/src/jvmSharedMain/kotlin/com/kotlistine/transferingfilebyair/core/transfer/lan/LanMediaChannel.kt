package com.kotlistine.transferingfilebyair.core.transfer.lan

import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.data.ByteSpan
import com.kotlistine.transferingfilebyair.core.transfer.data.coveredBytes
import com.kotlistine.transferingfilebyair.core.transfer.data.missingSpans
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Asked for at a time: small enough that a piece already on its way when a card lifts is through in a blink. */
private const val PieceBytes = 256 * 1024

/** Asked for ahead of what is being read: the next pieces are on their way while this one is used. */
private const val PiecesAhead = 2

/** Without a byte of a piece asked for for this long, the read fails (the other end may be an older build). */
private const val PieceTimeoutMillis = 10_000L

private const val DataChunkBytes = 64 * 1024

/**
 * The longest a pull holds back for the pieces a player is waiting for. Usually far less: a player with enough
 * buffered asks for nothing for a while. The limit keeps a pull going on a link too slow to ever fill that buffer.
 */
private const val PlayerFirstMillis = 2_000L

/** The longest a pull holds back for a card in the air (one that was never let go must not stop it for good). */
private const val CardHoldMaxMillis = 15_000L

/** Rounds of pieces in a row that may bring nothing new before a pull gives up. */
private const val PullRetries = 3

/**
 * Reads files on the other device over the link's files' connection, a piece at a time: what plays a video arriving
 * on a card from the frame it is at, and pulls a file whole once its card lands.
 *
 * Each end serves only what it [share]d. The reader asks ([LanFrame.MediaRead]), a few pieces ahead; the other end
 * answers each in chunks and says when it is done. Nothing is sent that wasn't asked for, so a reader that stops
 * reading stops the bytes.
 *
 * Every byte that comes over is kept, at its place, in one file per file read ([KeptFile]): a player reads again what
 * it already read from there, and a pull asks only for what is still missing, so a video watched while it arrived
 * crosses once. A pull also makes way: it asks for no new piece while a player waits for one (it plays now; the file
 * can wait) or while a card is in the air (its moves share the Wi-Fi).
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
    private val kept = ConcurrentHashMap<String, KeptFile>()

    /** Where to read a file from on this device: its kept copy for good ([settle]), or a whole pull's own file. */
    private val local = ConcurrentHashMap<String, String>()

    /** A whole pull's file, until the shelf keeps it elsewhere ([settle]) or no longer wants it ([forget]). */
    private val pulled = ConcurrentHashMap<String, File>()

    /** Players waiting for a piece right now. */
    private val playersWaiting = AtomicInteger()

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
        local[fileId] = uri
        pulled.remove(fileId)?.delete()
        kept.remove(fileId)?.discard()
    }

    override fun forget(fileId: String) {
        local.remove(fileId)
        pulled.remove(fileId)?.delete()
        kept.remove(fileId)?.discard()
    }

    override suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network> {
        local[fileId]?.let { uri ->
            val opened = withContext(Dispatchers.IO) { openLocal(uri, offset) }
            if (opened != null) return Result.Success(opened)
        }
        val channel = filesChannel() ?: return Result.Failure(DataError.Network.DISCONNECTED)
        return Result.Success(MediaStream(channel, fileId, offset))
    }

    override suspend fun fetch(
        fileId: String,
        sizeBytes: Long,
        onProgress: (Float) -> Unit,
    ): Result<String, DataError> = withContext(Dispatchers.IO) {
        local[fileId]?.let { return@withContext Result.Success(it) }
        val channel = filesChannel() ?: return@withContext Result.Failure(DataError.Network.DISCONNECTED)
        val file = try {
            keptOf(fileId)
        } catch (_: IOException) {
            return@withContext Result.Failure(DataError.Local.DISK_FULL)
        }
        var fruitless = 0
        while (true) {
            ensureActive()
            val have = file.spans()
            val covered = coveredBytes(sizeBytes, have)
            if (sizeBytes > 0) onProgress(covered.toFloat() / sizeBytes)
            val missing = missingSpans(sizeBytes, have, PieceBytes.toLong())
            if (missing.isEmpty()) break
            if (fruitless > PullRetries) return@withContext Result.Failure(DataError.Network.DISCONNECTED)
            // The player's pieces go first: it plays now, the file can wait.
            withTimeoutOrNull(PlayerFirstMillis) { while (playersWaiting.get() > 0) delay(10) }
            // So does a card in the air: its moves share the Wi-Fi.
            withTimeoutOrNull(CardHoldMaxMillis) { while (isHeld) delay(20) }
            val asked = missing.take(PiecesAhead).map { span -> ask(channel, fileId, span, forKeeps = true) }
            try {
                for (piece in asked) {
                    if (!runInterruptible { drain(piece, file) }) return@withContext Result.Failure(DataError.Network.DISCONNECTED)
                }
            } catch (e: CancellationException) {
                asked.forEach { callOff(channel, it) }
                throw e
            }
            if (coveredBytes(sizeBytes, file.spans()) <= covered) fruitless++
        }
        // All of it is here: the kept file is the file. Read from it as it is from now on.
        val location = file.finish()
        kept.remove(fileId)
        pulled[fileId] = file.file
        local[fileId] = location
        Result.Success(location)
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

    /** The link ended: what is being read from it fails, and nothing more is served on it. What came over stays. */
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

    private fun keptOf(fileId: String): KeptFile = kept.computeIfAbsent(fileId) {
        KeptFile(files.scratch("kept-${Random.nextLong().toULong()}"))
    }

    private fun ask(channel: SecureChannel, fileId: String, span: ByteSpan, forKeeps: Boolean): Piece {
        val piece = Piece(Random.nextLong(), span.start, span.length.toInt())
        pieces[piece.requestId] = piece
        channel.send(LanFrame.MediaRead(piece.requestId, fileId, span.start, piece.length, forKeeps))
        return piece
    }

    private fun callOff(channel: SecureChannel, piece: Piece) {
        if (pieces.remove(piece.requestId) != null) channel.sendQuietly(LanFrame.MediaCancel(piece.requestId))
    }

    /** Keeps all of [piece] in [file] as it comes. False when it failed (not shared, unreadable, no answer). */
    private fun drain(piece: Piece, file: KeptFile): Boolean {
        while (true) {
            when (val next = piece.next(PieceTimeoutMillis)) {
                null -> return false
                is Chunk -> file.write(next.offset, next.bytes)
                is PieceEnd -> return next.ok
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
     * A file read from [offset] on, for a player: from this device's copy once there is one ([settle], or a whole
     * pull), else from what is kept of it here, and over the link for the rest (kept too, as it comes).
     */
    private inner class MediaStream(
        private val channel: SecureChannel,
        private val fileId: String,
        offset: Long,
    ) : ByteStream {
        private var position = offset
        private var reader: KeptReader? = null
        private var copy: ByteStream? = null

        @Volatile
        private var isClosed = false

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (isClosed) throw IOException("Closed")
                if (copy == null) {
                    local[fileId]?.let { uri ->
                        openLocal(uri, position)?.let { opened ->
                            reader?.close()
                            reader = null
                            copy = opened
                        }
                    }
                }
                copy?.let { opened ->
                    val read = opened.read(buffer, offset, length)
                    if (read > 0) position += read
                    return read
                }
                val current = reader ?: KeptReader(channel, fileId, position, keptOf(fileId)).also { reader = it }
                try {
                    val read = current.read(buffer, offset, length)
                    if (read > 0) position += read
                    return read
                } catch (_: KeptFile.Finished) {
                    // The pull has just ended: on from its file.
                    current.close()
                    reader = null
                }
            }
        }

        override fun close() {
            isClosed = true
            reader?.close()
            copy?.close()
        }
    }

    /**
     * Reads the other device's file [fileId] from [offset] on: the parts [kept] already holds from there, the rest
     * asked for over the link [PiecesAhead] pieces ahead, each byte kept as it comes. Blocks; [close] stops it.
     */
    private inner class KeptReader(
        private val channel: SecureChannel,
        private val fileId: String,
        offset: Long,
        private val kept: KeptFile,
    ) {
        private val ahead = ArrayDeque<Segment>()
        private var nextOffset = offset
        private var reachedEnd = false
        private var current: Chunk? = null
        private var currentAt = 0

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                current?.let { chunk ->
                    val count = minOf(length, chunk.bytes.size - currentAt)
                    chunk.bytes.copyInto(buffer, offset, currentAt, currentAt + count)
                    currentAt += count
                    if (currentAt == chunk.bytes.size) current = null
                    return count
                }
                askAhead()
                when (val segment = ahead.firstOrNull() ?: return -1) {
                    is Segment.Kept -> {
                        val read = kept.read(segment.at, buffer, offset, minOf(length.toLong(), segment.left).toInt())
                        if (read <= 0) throw IOException("The kept copy of $fileId is shorter than it was")
                        segment.at += read
                        segment.left -= read
                        if (segment.left == 0L) ahead.removeFirst()
                        return read
                    }
                    is Segment.Asked -> {
                        playersWaiting.incrementAndGet()
                        val next = try {
                            segment.piece.next(PieceTimeoutMillis)
                        } finally {
                            playersWaiting.decrementAndGet()
                        }
                        when (next) {
                            null -> throw IOException("No answer for a piece of $fileId")
                            is Chunk -> {
                                kept.write(next.offset, next.bytes)
                                current = next
                                currentAt = 0
                            }
                            is PieceEnd -> {
                                if (!next.ok) throw IOException("$fileId can't be read")
                                ahead.removeFirst()
                                // Fewer bytes than asked: the file ended there; what comes after it is empty.
                                if (segment.piece.received < segment.piece.length) {
                                    reachedEnd = true
                                    clearAhead()
                                }
                            }
                        }
                    }
                }
            }
        }

        /** Lines up what comes next: from the kept copy where it holds the bytes, else asked for, up to the next kept part. */
        private fun askAhead() {
            while (!reachedEnd && ahead.size < PiecesAhead) {
                val keptHere = kept.keptFrom(nextOffset)
                if (keptHere > 0) {
                    val length = minOf(keptHere, PieceBytes.toLong())
                    ahead.addLast(Segment.Kept(nextOffset, length))
                    nextOffset += length
                } else {
                    val length = minOf(kept.gapFrom(nextOffset), PieceBytes.toLong())
                    ahead.addLast(Segment.Asked(ask(channel, fileId, ByteSpan(nextOffset, nextOffset + length), forKeeps = false)))
                    nextOffset += length
                }
            }
        }

        private fun clearAhead() {
            ahead.forEach { if (it is Segment.Asked) callOff(channel, it.piece) }
            ahead.clear()
        }

        fun close() {
            clearAhead()
            current = null
        }
    }

    private sealed interface Segment {
        class Kept(var at: Long, var left: Long) : Segment

        class Asked(val piece: Piece) : Segment
    }

    /** One piece asked for, [length] bytes from [start]: its bytes as they come, then how it ended. */
    private class Piece(val requestId: Long, val start: Long, val length: Int) {
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

        /** The next bytes (with where they go in the file), or how it ended; null after [timeoutMillis] with nothing. */
        fun next(timeoutMillis: Long): Any? = when (val next = arrivals.poll(timeoutMillis, TimeUnit.MILLISECONDS)) {
            is ByteArray -> Chunk(start + received, next).also { received += next.size }
            else -> next
        }
    }

    private class Chunk(val offset: Long, val bytes: ByteArray)

    private class PieceEnd(val ok: Boolean)
}

/**
 * What has come over of one of the other device's files, each byte at its own place in [file], and which parts that
 * is. Shared by its players and its pull. Once all of it is here, the file is the file ([finish]).
 */
private class KeptFile(val file: File) {
    /** Read from a kept file whose pull has ended meanwhile: read on from the pull's file. */
    class Finished : IOException("The pull has ended")

    private val access = RandomAccessFile(file, "rw")
    private val kept = ArrayList<ByteSpan>()
    private var isOpen = true

    @Synchronized
    fun write(offset: Long, bytes: ByteArray) {
        if (!isOpen) return
        access.seek(offset)
        access.write(bytes)
        add(ByteSpan(offset, offset + bytes.size))
    }

    @Synchronized
    fun read(offset: Long, buffer: ByteArray, at: Int, length: Int): Int {
        if (!isOpen) throw Finished()
        access.seek(offset)
        return access.read(buffer, at, length)
    }

    /** How many bytes from [offset] on are here, without a gap. */
    @Synchronized
    fun keptFrom(offset: Long): Long = kept.firstOrNull { offset >= it.start && offset < it.end }?.let { it.end - offset } ?: 0L

    /** How many bytes from [offset] on are missing before the next part that is here (as good as endless after the last). */
    @Synchronized
    fun gapFrom(offset: Long): Long = kept.firstOrNull { it.start > offset }?.let { it.start - offset } ?: Long.MAX_VALUE

    @Synchronized
    fun spans(): List<ByteSpan> = kept.toList()

    /** Done with: the file is complete, closed, and read as a whole from its address from now on. */
    @Synchronized
    fun finish(): String {
        isOpen = false
        access.close()
        return file.toURI().toString()
    }

    /** No longer wanted. */
    @Synchronized
    fun discard() {
        if (isOpen) {
            isOpen = false
            runCatching { access.close() }
        }
        file.delete()
    }

    /** Adds [span], keeping the parts sorted and joined where they touch. */
    private fun add(span: ByteSpan) {
        val joined = (kept + span).sortedBy { it.start }
        kept.clear()
        for (part in joined) {
            val last = kept.lastOrNull()
            if (last != null && part.start <= last.end) {
                kept[kept.lastIndex] = ByteSpan(last.start, maxOf(last.end, part.end))
            } else {
                kept += part
            }
        }
    }
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
