package com.kotlistine.transferingfilebyair.core.transfer.nearby

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** How long the other phone has to start sending a piece it was asked for. */
private const val OpenTimeoutMillis = 10_000L

/**
 * A file is read in pieces this big. Small, because every byte shares the one connection with the card's moves: a
 * piece crosses Wi-Fi in a few hundredths of a second, and holds about half a second of a phone's video.
 */
private const val PieceBytes = 1L * 1024 * 1024

private const val CopyBufferBytes = 64 * 1024

private const val Tag = "AirLink"

/**
 * [MediaChannel] on the Nearby link. A file is read a piece at a time: a [LinkFrame.RangeRequest] asks for a piece,
 * the sharing phone reads it into a STREAM payload and says which payload it is ([LinkFrame.RangeOpened]).
 *
 * Two things about Nearby shape this, both seen on phones:
 * - It carries every payload over one connection, and holds only a few chunks of a stream for the app. A stream the
 *   app stops reading blocks the whole connection, so every piece is drained into a file as it arrives
 *   ([DrainedPiece]) and the player reads from there.
 * - Cancelling a stream while it is still sending can stall the whole connection too. So no piece is ever cancelled:
 *   each has a length and ends by itself, and one nobody wants any more is drained and thrown away.
 *
 * Driven by [NearbyPeerLink], which owns the connection: it hands over range frames, stream payloads and their
 * updates, link speed changes, and the end of the link. All of those arrive on the main thread.
 */
internal class NearbyMediaChannel(
    context: Context,
    private val client: ConnectionsClient,
    private val scope: CoroutineScope,
    /** The linked phone's endpoint, once its link counts; null otherwise. Main thread only. */
    private val endpoint: () -> String?,
) : MediaChannel {

    private val appContext = context.applicationContext

    /** Where arriving pieces are drained to; emptied of anything a previous run left behind on first use. */
    private val piecesDir by lazy {
        File(appContext.cacheDir, "live-ranges").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Volatile
    private var quality = BandwidthInfo.Quality.UNKNOWN

    /** Files this phone lets the other read: file id → uri. */
    private val shared = ConcurrentHashMap<String, String>()

    private val nextRequestId = AtomicLong(1)

    /** Pieces this phone asked for, by request id, completed with the piece (null: refused or the link went). */
    private val requests = ConcurrentHashMap<Long, CompletableDeferred<DrainedPiece?>>()

    /** Request ids by the payload that answers them, until that payload arrives. */
    private val answers = ConcurrentHashMap<Long, Long>()

    /**
     * Pieces that arrived before the frame saying which request they answer: Nearby calls back on several threads,
     * so a stream can overtake the frame sent before it. They drain meanwhile.
     */
    private val early = ConcurrentHashMap<Long, DrainedPiece>()

    /** What this phone is reading into stream payloads it sends, by payload id. */
    private val sending = ConcurrentHashMap<Long, InputStream>()

    // Bluetooth is LOW (BLE) or MEDIUM (classic): far too slow for a phone's video. Wi-Fi is HIGH.
    override val isFast: Boolean get() = quality >= BandwidthInfo.Quality.HIGH

    override fun share(fileId: String, uri: String) {
        shared[fileId] = uri
    }

    override fun unshare(fileId: String) {
        shared.remove(fileId)
    }

    override suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network> {
        val first = openPiece(fileId, offset) ?: return Result.Failure(DataError.Network.DISCONNECTED)
        return Result.Success(PiecedStream(fileId, offset, first))
    }

    /** Asks for the piece of [fileId] at [offset]; null if it doesn't come. It is already draining when it does. */
    private suspend fun openPiece(fileId: String, offset: Long): DrainedPiece? {
        val requestId = nextRequestId.getAndIncrement()
        val answer = CompletableDeferred<DrainedPiece?>()
        requests[requestId] = answer
        val askedAt = System.currentTimeMillis()
        val asked = withContext(Dispatchers.Main.immediate) {
            val endpoint = endpoint() ?: return@withContext false
            val request = LinkFrame.RangeRequest(requestId, offset, PieceBytes, fileId)
            client.sendPayload(endpoint, Payload.fromBytes(request.encode()))
            true
        }
        val piece = if (asked) withTimeoutOrNull(OpenTimeoutMillis) { answer.await() } else null
        requests.remove(requestId)
        if (piece == null) {
            // It may still turn up after the wait: it is dropped then (see deliver).
            Log.w(Tag, "Piece of $fileId at $offset: no answer (asked: $asked)")
            return null
        }
        Log.i(Tag, "Piece of $fileId at $offset opened in ${System.currentTimeMillis() - askedAt} ms")
        return piece
    }

    fun onBandwidthChanged(info: BandwidthInfo) {
        quality = info.quality
        Log.i(Tag, "Link quality ${info.quality} (3 is Wi-Fi): live video ${if (isFast) "on" else "off"}")
    }

    fun onFrame(frame: LinkFrame) {
        when (frame) {
            is LinkFrame.RangeRequest -> serve(frame)
            is LinkFrame.RangeOpened -> {
                val piece = early.remove(frame.payloadId)
                if (piece != null) deliver(frame.requestId, piece) else answers[frame.payloadId] = frame.requestId
            }
            is LinkFrame.RangeRefused -> {
                Log.w(Tag, "Piece request ${frame.requestId} refused by the other phone")
                requests[frame.requestId]?.complete(null)
            }
            else -> Unit
        }
    }

    /**
     * A stream payload arrived: a piece this phone asked for. It starts draining at once, whether or not its request
     * is known yet: a stream left unread blocks everything else arriving on the link, the frame naming its request
     * included.
     */
    fun onStream(payload: Payload) {
        val input = payload.asStream()?.asInputStream() ?: return
        val piece = try {
            DrainedPiece(input, File(piecesDir, "piece-${payload.id}.part"))
        } catch (_: IOException) {
            discard(input)
            return
        }
        val requestId = answers.remove(payload.id)
        if (requestId != null) {
            deliver(requestId, piece)
            return
        }
        early[payload.id] = piece
        // Never claimed (its request was given up on): dropped after a while.
        scope.launch {
            delay(OpenTimeoutMillis * 2)
            early.remove(payload.id)?.close()
        }
    }

    /** Returns whether the update was for a piece this phone is sending (and so handled here). */
    fun onTransferUpdate(update: PayloadTransferUpdate): Boolean {
        val input = sending[update.payloadId] ?: return false
        when (update.status) {
            PayloadTransferUpdate.Status.SUCCESS,
            PayloadTransferUpdate.Status.FAILURE,
            PayloadTransferUpdate.Status.CANCELED,
            -> {
                if (update.status != PayloadTransferUpdate.Status.SUCCESS) {
                    Log.w(Tag, "Piece ${update.payloadId} ended: ${update.status} after ${update.bytesTransferred} bytes")
                }
                sending.remove(update.payloadId)
                input.closeQuietly()
            }
            else -> Unit
        }
        return true
    }

    /** The link is gone: every open piece fails, and nothing stays shared. */
    fun onLinkDropped() {
        quality = BandwidthInfo.Quality.UNKNOWN
        requests.values.forEach { it.complete(null) }
        answers.clear()
        early.values.forEach { it.close() }
        early.clear()
        sending.values.forEach { it.closeQuietly() }
        sending.clear()
        shared.clear()
    }

    private fun deliver(requestId: Long, piece: DrainedPiece) {
        val request = requests[requestId]
        if (request != null && request.complete(piece)) return
        // The asker gave up waiting. Not cancelled (that can stall the link): it drains, and is dropped.
        piece.close()
    }

    private fun serve(request: LinkFrame.RangeRequest) {
        val endpoint = endpoint() ?: return
        val uri = shared[request.fileId]
        if (uri == null) {
            Log.w(Tag, "Piece of ${request.fileId} refused: not shared")
            client.sendPayload(endpoint, Payload.fromBytes(LinkFrame.RangeRefused(request.requestId).encode()))
            return
        }
        scope.launch {
            val input = withContext(Dispatchers.IO) { openAt(uri, request.offset, request.length) }
            if (input == null || endpoint() != endpoint) {
                input?.closeQuietly()
                client.sendPayload(endpoint, Payload.fromBytes(LinkFrame.RangeRefused(request.requestId).encode()))
                return@launch
            }
            val payload = Payload.fromStream(input)
            sending[payload.id] = input
            client.sendPayload(endpoint, Payload.fromBytes(LinkFrame.RangeOpened(request.requestId, payload.id).encode()))
            client.sendPayload(endpoint, payload).addOnFailureListener { e ->
                Log.w(Tag, "Piece ${payload.id} couldn't be sent", e)
                sending.remove(payload.id)
                input.closeQuietly()
            }
        }
    }

    /** At most [length] bytes of the file at [uri] from [offset]: the stream ends by itself after them. */
    private fun openAt(uri: String, offset: Long, length: Long): InputStream? = try {
        val descriptor = appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
        descriptor?.let {
            val input = ParcelFileDescriptor.AutoCloseInputStream(it).apply { channel.position(offset) }
            LimitedInputStream(input, length)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * A file read from [start] to its end, a piece at a time. The next piece is asked for once half of the current
     * one has been read: early enough that the player rarely waits, late enough that a player only glancing at a part
     * of the file (its header, before jumping to its index at the end) doesn't make it fetch more.
     * A piece shorter than asked for is the file's last.
     */
    private inner class PiecedStream(
        private val fileId: String,
        start: Long,
        first: DrainedPiece,
    ) : ByteStream {
        private var current = first
        private var nextOffset = start + PieceBytes
        private var next: Deferred<DrainedPiece?>? = null

        @Volatile
        private var isClosed = false

        private fun prefetch(): Deferred<DrainedPiece?> {
            val offset = nextOffset
            return scope.async { openPiece(fileId, offset) }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (isClosed) throw IOException("Range closed")
                val read = current.read(buffer, offset, length)
                if (next == null && current.position >= PieceBytes / 2) next = prefetch()
                if (read >= 0) return read
                if (current.size < PieceBytes) return -1
                // Interrupted (the player gave up on this range): runBlocking throws, and the read with it.
                val following = runBlocking { (next ?: prefetch()).await() } ?: throw IOException("Next piece didn't come")
                current.close()
                current = following
                nextOffset += PieceBytes
                next = null
            }
        }

        override fun close() {
            if (isClosed) return
            isClosed = true
            current.close()
            // A piece still on its way is drained and dropped once it arrives.
            next?.let { pending -> scope.launch { pending.await()?.close() } }
        }
    }
}

/**
 * One piece arriving as a Nearby stream payload, drained into [file] as fast as it arrives (so the connection never
 * waits on the player), and read from there. Closing it before it has all arrived lets the rest drain and drops it.
 */
private class DrainedPiece(
    private val input: InputStream,
    private val file: File,
) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val writer = FileOutputStream(file)
    private val reader = RandomAccessFile(file, "r")

    // Guarded by lock.
    private var written = 0L
    private var isEnded = false
    private var failure: Exception? = null
    private var isClosed = false

    /** How far it has been read. Read by the player's loader thread only. */
    var position = 0L
        private set

    /** How many bytes the piece had, once it has all arrived. */
    val size: Long get() = lock.withLock { written }

    init {
        thread(name = "AirPieceDrain", isDaemon = true) { drain() }
    }

    private fun drain() {
        val buffer = ByteArray(CopyBufferBytes)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                lock.withLock {
                    // Nobody wants it any more: the rest is read and thrown away.
                    if (!isClosed) writer.write(buffer, 0, read)
                    written += read
                    arrived.signalAll()
                }
            }
        } catch (e: Exception) {
            lock.withLock { failure = e }
        } finally {
            input.closeQuietly()
            lock.withLock {
                isEnded = true
                arrived.signalAll()
                try {
                    writer.close()
                } catch (_: IOException) {
                }
                if (isClosed) file.delete()
            }
        }
    }

    /** Like [InputStream.read]: waits for bytes, -1 once the piece has all been read. Throws if the piece broke. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val available = lock.withLock {
            while (position >= written && !isEnded) arrived.await()
            if (position >= written) {
                failure?.let { throw IOException("Piece broke", it) }
                return -1
            }
            written - position
        }
        reader.seek(position)
        val read = reader.read(buffer, offset, minOf(length.toLong(), available).toInt())
        if (read > 0) position += read
        return read
    }

    fun close() {
        lock.withLock {
            if (isClosed) return
            isClosed = true
            if (isEnded) file.delete()
        }
        try {
            reader.close()
        } catch (_: IOException) {
        }
    }
}

/** [input], ending after [limit] bytes. */
private class LimitedInputStream(private val input: InputStream, private var limit: Long) : InputStream() {
    override fun read(): Int {
        if (limit <= 0) return -1
        val byte = input.read()
        if (byte >= 0) limit--
        return byte
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (limit <= 0) return -1
        val read = input.read(buffer, offset, minOf(length.toLong(), limit).toInt())
        if (read > 0) limit -= read
        return read
    }

    override fun close() = input.close()
}

/** Reads [input] to its end and throws it away, on a thread of its own. */
private fun discard(input: InputStream) {
    thread(name = "AirPieceDiscard", isDaemon = true) {
        try {
            val buffer = ByteArray(CopyBufferBytes)
            while (input.read(buffer) >= 0) Unit
        } catch (_: Exception) {
        } finally {
            input.closeQuietly()
        }
    }
}

private fun InputStream.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
