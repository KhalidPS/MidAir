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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How long the other phone has to start sending a piece it was asked for. */
private const val OpenTimeoutMillis = 10_000L

/**
 * A file is read in pieces this big. Small, because every byte shares the one connection with the card's moves: a
 * piece crosses Wi-Fi in a few hundredths of a second, and holds about half a second of a phone's video.
 */
private const val PieceBytes = 1L * 1024 * 1024

/** Pieces on their way at once, past the one being read. More start a video faster but hold up the card's moves. */
private const val PiecesAhead = 3

private const val CopyBufferBytes = 64 * 1024

/** Pieces a [NearbyMediaChannel.fetch] asks for at once, to keep the link busy despite each request's round trip. */
private const val FetchPiecesAhead = 3

/** Rounds of pieces in a row that may bring nothing new before a fetch gives up. */
private const val FetchRetries = 3

/**
 * The longest a fetch holds back for the pieces a player is waiting for. Usually far less: a player whose buffer is
 * full asks for nothing for seconds. The limit keeps a fetch going on a link too slow to ever fill that buffer.
 */
private const val PlayerFirstMillis = 2_000L

/** How long the other phone has to hash its whole file (about 5 s for 1 GB on a mid-range phone). */
private const val DigestTimeoutMillis = 60_000L

/** Room left on this phone after a fetch, on top of the fetched file and the copy kept of it. */
private const val SpareBytes = 64L * 1024 * 1024

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
 *   each has a length and ends by itself, and one nobody wants any more is drained and only kept.
 *
 * Every piece that arrives whole is kept, so a video played from the other phone is mostly here already when its
 * card is let go: [fetch] asks only for the rest (while no player waits), puts the file together, and checks it
 * against the other phone's SHA-256. From then on the file is read from this phone ([local]).
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

    /** The way that doesn't go through Nearby, when both phones are on one Wi-Fi network. */
    private val lan = LanFileServer(context)

    /** Where arriving pieces are drained to; emptied of anything a previous run left behind on first use. */
    private val piecesDir by lazy {
        File(appContext.cacheDir, "live-ranges").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /** The link's bandwidth as Nearby reports it (`BandwidthInfo.Quality`; 3 is Wi-Fi). */
    @Volatile
    var quality = BandwidthInfo.Quality.UNKNOWN
        private set

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

    /** Which file and where in it each piece asked for belongs, by request id, until the piece arrives. */
    private val origins = ConcurrentHashMap<Long, PieceOrigin>()

    /** Pieces that arrived whole, by the file they belong to. Guarded by [keptLock]. */
    private val kept = HashMap<String, MutableList<KeptPiece>>()
    private val keptLock = Any()

    /** The other phone's files that are on this one now, by file id: read from here instead of over the link. */
    private val local = ConcurrentHashMap<String, LocalCopy>()

    /** Digests this phone asked for, by request id, completed with the SHA-256 (null: refused or the link went). */
    private val digests = ConcurrentHashMap<Long, CompletableDeferred<ByteArray?>>()

    /** Pieces asked for by open streams (players) that haven't all arrived yet: a fetch holds back while there are. */
    private val playerPieces = MutableStateFlow(0)

    // Bluetooth is LOW (BLE) or MEDIUM (classic): far too slow for a phone's video. Wi-Fi is HIGH.
    override val isFast: Boolean get() = quality >= BandwidthInfo.Quality.HIGH

    private val fastUpdates = MutableStateFlow(false)
    override val isFastUpdates: StateFlow<Boolean> = fastUpdates.asStateFlow()

    override fun share(fileId: String, uri: String): String? {
        shared[fileId] = uri
        return lan.share(fileId, uri)
    }

    override fun unshare(fileId: String) {
        shared.remove(fileId)
        lan.unshare(fileId)
    }

    override suspend fun canReach(url: String): Boolean {
        val startedAt = System.currentTimeMillis()
        val reachable = withContext(Dispatchers.IO) { answersFromHere(url) }
        Log.i(Tag, "Wi-Fi path ${if (reachable) "reachable" else "not reachable"} (${System.currentTimeMillis() - startedAt} ms)")
        return reachable
    }

    override suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network> {
        openLocal(fileId, offset)?.let { return Result.Success(LocalStream(it)) }
        val first = openPiece(fileId, offset, PieceBytes, forPlayer = true)
            ?: return Result.Failure(DataError.Network.DISCONNECTED)
        return Result.Success(PiecedStream(fileId, offset, first))
    }

    /**
     * Asks for [length] bytes of [fileId] at [offset]; null if they don't come. The piece is already draining when they
     * do. [forPlayer] pieces count in [playerPieces] until they have all arrived.
     */
    private suspend fun openPiece(fileId: String, offset: Long, length: Long, forPlayer: Boolean): DrainedPiece? {
        val requestId = nextRequestId.getAndIncrement()
        val answer = CompletableDeferred<DrainedPiece?>()
        requests[requestId] = answer
        origins[requestId] = PieceOrigin(fileId, offset)
        if (forPlayer) playerPieces.update { it + 1 }
        var piece: DrainedPiece? = null
        try {
            val askedAt = System.currentTimeMillis()
            val asked = withContext(Dispatchers.Main.immediate) {
                val endpoint = endpoint() ?: return@withContext false
                val request = LinkFrame.RangeRequest(requestId, offset, length, fileId)
                client.sendPayload(endpoint, Payload.fromBytes(request.encode()))
                true
            }
            piece = if (asked) withTimeoutOrNull(OpenTimeoutMillis) { answer.await() } else null
            if (piece == null) {
                // It may still turn up after the wait: it is kept then, but nobody reads it (see deliver).
                Log.w(Tag, "Piece of $fileId at $offset: no answer (asked: $asked)")
                if (!asked) origins.remove(requestId)
                return null
            }
            Log.i(Tag, "Piece of $fileId at $offset opened in ${System.currentTimeMillis() - askedAt} ms")
            return piece
        } finally {
            requests.remove(requestId)
            if (forPlayer) {
                val arriving = piece
                if (arriving == null) {
                    playerPieces.update { it - 1 }
                } else {
                    scope.launch {
                        arriving.ended.await()
                        playerPieces.update { it - 1 }
                    }
                }
            }
        }
    }

    override suspend fun fetch(fileId: String, sizeBytes: Long, onProgress: (Float) -> Unit): Result<String, DataError> {
        local[fileId]?.let { return Result.Success(it.uri) }
        val startedAt = System.currentTimeMillis()
        // The fetched file, and the copy kept of it before this one goes.
        val room = 2 * sizeBytes - coveredBytes(sizeBytes, keptOf(fileId).map { it.span }) + SpareBytes
        if (piecesDir.usableSpace < room) {
            Log.w(Tag, "No room to fetch $fileId ($sizeBytes bytes)")
            return Result.Failure(DataError.Local.DISK_FULL)
        }
        val keptAtStart = coveredBytes(sizeBytes, keptOf(fileId).map { it.span })
        return coroutineScope {
            // Hashed by the other phone while the rest of the file comes over.
            val digest = async { askDigest(fileId) }
            if (!fillIn(fileId, sizeBytes, onProgress)) {
                digest.cancel()
                return@coroutineScope Result.Failure(DataError.Network.DISCONNECTED)
            }
            val copy = withContext(Dispatchers.IO) { assemble(fileId, sizeBytes) }
            if (copy == null) {
                digest.cancel()
                return@coroutineScope Result.Failure(DataError.Local.UNKNOWN)
            }
            val expected = digest.await()
            if (expected == null || !expected.contentEquals(copy.sha256)) {
                Log.w(Tag, "Fetched $fileId doesn't match the other phone's (digest ${if (expected == null) "missing" else "differs"})")
                copy.file.delete()
                dropKept(fileId)
                return@coroutineScope Result.Failure(DataError.Local.UNKNOWN)
            }
            val uri = Uri.fromFile(copy.file).toString()
            local[fileId] = LocalCopy(uri, owned = copy.file)
            dropKept(fileId)
            Log.i(
                Tag,
                "Fetched $fileId ($sizeBytes bytes, $keptAtStart already here) in ${System.currentTimeMillis() - startedAt} ms",
            )
            Result.Success(uri)
        }
    }

    /**
     * Asks for the parts of [fileId] not kept yet, [FetchPiecesAhead] at a time, each round once no player waits for a
     * piece. Returns whether all of it is kept now.
     */
    private suspend fun fillIn(fileId: String, sizeBytes: Long, onProgress: (Float) -> Unit): Boolean {
        var fruitless = 0
        while (true) {
            val have = keptOf(fileId).map { it.span }
            val covered = coveredBytes(sizeBytes, have)
            onProgress(if (sizeBytes > 0) covered.toFloat() / sizeBytes else 1f)
            val missing = missingSpans(sizeBytes, have, PieceBytes)
            if (missing.isEmpty()) return true
            if (fruitless > FetchRetries) {
                Log.w(Tag, "Fetching $fileId: gave up with ${sizeBytes - covered} bytes missing")
                return false
            }
            // The player's pieces go first: it plays now, the file can wait.
            withTimeoutOrNull(PlayerFirstMillis) { playerPieces.first { it <= 0 } }
            val pieces = coroutineScope {
                missing.take(FetchPiecesAhead)
                    .map { span -> async { openPiece(fileId, span.start, span.length, forPlayer = false) } }
                    .awaitAll()
            }
            // Kept as they end (they were claimed when they arrived); nobody reads them meanwhile.
            pieces.filterNotNull().forEach { piece ->
                piece.ended.await()
                piece.close()
            }
            if (coveredBytes(sizeBytes, keptOf(fileId).map { it.span }) <= covered) fruitless++
        }
    }

    /** The SHA-256 of the other phone's file [fileId]; null if it doesn't come. */
    private suspend fun askDigest(fileId: String): ByteArray? {
        val requestId = nextRequestId.getAndIncrement()
        val answer = CompletableDeferred<ByteArray?>()
        digests[requestId] = answer
        try {
            val asked = withContext(Dispatchers.Main.immediate) {
                val endpoint = endpoint() ?: return@withContext false
                client.sendPayload(endpoint, Payload.fromBytes(LinkFrame.DigestRequest(requestId, fileId).encode()))
                true
            }
            return if (asked) withTimeoutOrNull(DigestTimeoutMillis) { answer.await() } else null
        } finally {
            digests.remove(requestId)
        }
    }

    /** Puts [fileId] together from its kept pieces, hashing it on the way. Null if a byte is missing or unreadable. */
    private fun assemble(fileId: String, sizeBytes: Long): Assembled? {
        val pieces = keptOf(fileId)
        val plan = assemblyPlan(sizeBytes, pieces.map { it.span }) ?: return null
        val target = File(piecesDir, "fetched-${nextRequestId.getAndIncrement()}")
        val sha256 = MessageDigest.getInstance("SHA-256")
        return try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(CopyBufferBytes)
                for ((index, span) in plan) {
                    val piece = pieces[index]
                    RandomAccessFile(piece.file, "r").use { input ->
                        input.seek(span.start - piece.span.start)
                        var left = span.length
                        while (left > 0) {
                            val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                            if (read < 0) throw IOException("${piece.file.name} is shorter than it was")
                            output.write(buffer, 0, read)
                            sha256.update(buffer, 0, read)
                            left -= read
                        }
                    }
                }
            }
            Assembled(target, sha256.digest())
        } catch (e: IOException) {
            Log.w(Tag, "Couldn't put $fileId together", e)
            target.delete()
            null
        }
    }

    override fun settle(fileId: String, uri: String) {
        local.put(fileId, LocalCopy(uri, owned = null))?.owned?.delete()
        dropKept(fileId)
        Log.i(Tag, "$fileId is on this phone for good: read from here")
    }

    override fun forget(fileId: String) {
        local.remove(fileId)?.owned?.delete()
        dropKept(fileId)
    }

    /** The other phone's file [fileId] from [offset], from its copy on this phone; null when there is none (yet). */
    private fun openLocal(fileId: String, offset: Long): InputStream? =
        local[fileId]?.let { openAt(it.uri, offset, Long.MAX_VALUE) }

    private fun keepPiece(fileId: String, piece: KeptPiece) {
        if (local.containsKey(fileId)) {
            // A late piece of a file that is here already.
            piece.file.delete()
            return
        }
        synchronized(keptLock) { kept.getOrPut(fileId) { mutableListOf() } += piece }
    }

    private fun keptOf(fileId: String): List<KeptPiece> = synchronized(keptLock) { kept[fileId].orEmpty().toList() }

    private fun dropKept(fileId: String) {
        val pieces = synchronized(keptLock) { kept.remove(fileId) }
        // A player may still be reading one: its open file stays readable after the delete.
        pieces?.forEach { it.file.delete() }
    }

    fun onBandwidthChanged(info: BandwidthInfo) {
        quality = info.quality
        fastUpdates.value = isFast
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
                Log.w(Tag, "Request ${frame.requestId} refused by the other phone")
                requests[frame.requestId]?.complete(null)
                digests[frame.requestId]?.complete(null)
            }
            is LinkFrame.DigestRequest -> serveDigest(frame)
            is LinkFrame.Digest -> digests[frame.requestId]?.complete(frame.sha256)
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
            DrainedPiece(input, File(piecesDir, "piece-${payload.id}.part"), scope, ::keepPiece)
        } catch (_: IOException) {
            scope.discard(input)
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
        fastUpdates.value = false
        requests.values.forEach { it.complete(null) }
        answers.clear()
        early.values.forEach { it.close() }
        early.clear()
        sending.values.forEach { it.closeQuietly() }
        sending.clear()
        origins.clear()
        digests.values.forEach { it.complete(null) }
        shared.clear()
        lan.unshareAll()
        // What is kept, or on this phone already, stays: a received video plays on without the link.
    }

    private fun deliver(requestId: Long, piece: DrainedPiece) {
        origins.remove(requestId)?.let(piece::claim)
        val request = requests[requestId]
        if (request != null && request.complete(piece)) return
        // The asker gave up waiting. Not cancelled (that can stall the link): it drains, and is only kept.
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

    /** Answers a [LinkFrame.DigestRequest] with the SHA-256 of a shared file, hashed off the main thread. */
    private fun serveDigest(request: LinkFrame.DigestRequest) {
        val endpoint = endpoint() ?: return
        val uri = shared[request.fileId]
        scope.launch {
            val sha256 = uri?.let { withContext(Dispatchers.IO) { sha256Of(it) } }
            if (endpoint() != endpoint) return@launch
            val answer = if (sha256 != null) {
                LinkFrame.Digest(request.requestId, sha256)
            } else {
                Log.w(Tag, "Digest of ${request.fileId} refused: ${if (uri == null) "not shared" else "unreadable"}")
                LinkFrame.RangeRefused(request.requestId)
            }
            client.sendPayload(endpoint, Payload.fromBytes(answer.encode()))
        }
    }

    private fun sha256Of(uri: String): ByteArray? = try {
        appContext.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val sha256 = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CopyBufferBytes)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha256.update(buffer, 0, read)
            }
            sha256.digest()
        }
    } catch (_: Exception) {
        null
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
     * A file read from [start] to its end, a piece at a time, with [PiecesAhead] pieces on their way at once: each
     * piece costs a request's round trip, so one at a time leaves the link mostly idle.
     *
     * Pieces ahead are asked for once half of the current one has been read: early enough that the player rarely
     * waits, late enough that a player only glancing at a part of the file (its header, before jumping to its index
     * at the end) doesn't make it fetch more. A piece shorter than asked for is the file's last.
     *
     * Once the file is on this phone ([local]), it goes on from there at the next piece, without the link.
     */
    private inner class PiecedStream(
        private val fileId: String,
        start: Long,
        first: DrainedPiece,
    ) : ByteStream {
        private var current = first

        /** Where [current] starts in the file. */
        private var currentStart = start

        /** The rest of the file from this phone's copy, once there is one. */
        private var fromHere: InputStream? = null

        /** Pieces asked for after [current], in file order. */
        private val ahead = ArrayDeque<Deferred<DrainedPiece?>>()

        /** Where the next piece to ask for starts. */
        private var nextOffset = start + PieceBytes

        @Volatile
        private var isClosed = false

        private fun fillAhead() {
            // On this phone already: the next piece comes from here.
            if (local.containsKey(fileId)) return
            while (ahead.size < PiecesAhead) {
                val offset = nextOffset
                ahead.addLast(scope.async { openPiece(fileId, offset, PieceBytes, forPlayer = true) })
                nextOffset += PieceBytes
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (isClosed) throw IOException("Range closed")
                fromHere?.let { return it.read(buffer, offset, length) }
                val read = current.read(buffer, offset, length)
                if (current.position >= PieceBytes / 2) fillAhead()
                if (read >= 0) return read
                if (current.size < PieceBytes) return -1
                val next = currentStart + PieceBytes
                if (goOnFromHere(next)) continue
                if (ahead.isEmpty()) fillAhead()
                // Interrupted (the player gave up on this range): runBlocking throws, and the read with it.
                val following = ahead.removeFirstOrNull()?.let { runBlocking { it.await() } }
                if (following == null) {
                    // Refused, most likely because the file is here now and the other phone let go of it.
                    if (goOnFromHere(next)) continue
                    throw IOException("Next piece didn't come")
                }
                current.close()
                current = following
                currentStart = next
            }
        }

        /** Switches to this phone's copy of the file from [position], if there is one. */
        private fun goOnFromHere(position: Long): Boolean {
            val input = openLocal(fileId, position) ?: return false
            Log.i(Tag, "$fileId goes on from this phone's copy at $position")
            current.close()
            dropAhead()
            fromHere = input
            return true
        }

        private fun dropAhead() {
            // Pieces still on their way are drained and dropped as they arrive.
            ahead.forEach { pending -> scope.launch { pending.await()?.close() } }
            ahead.clear()
        }

        override fun close() {
            if (isClosed) return
            isClosed = true
            current.close()
            fromHere?.closeQuietly()
            dropAhead()
        }
    }
}

/** Which file a piece belongs to, and where in it it starts. */
private class PieceOrigin(val fileId: String, val offset: Long)

/** A piece that arrived whole: the bytes [span] of its file, in [file]. */
private class KeptPiece(val span: ByteSpan, val file: File)

/** The other phone's file on this one, at [uri]. [owned]: this channel's own copy, deleted when let go. */
private class LocalCopy(val uri: String, val owned: File?)

private class Assembled(val file: File, val sha256: ByteArray)

/** The other phone's file, read from its copy on this phone. */
private class LocalStream(private val input: InputStream) : ByteStream {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)

    override fun close() = input.closeQuietly()
}

/**
 * One piece arriving as a Nearby stream payload, drained into [file] as fast as it arrives (so the connection never
 * waits on the player), and read from there. Once it has all arrived and is [claim]ed, its file goes to [keep] (for
 * putting the whole file together later); otherwise the file is deleted once the piece is closed and has ended.
 */
private class DrainedPiece(
    private val input: InputStream,
    private val file: File,
    scope: CoroutineScope,
    private val keep: (fileId: String, piece: KeptPiece) -> Unit,
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
    private var origin: PieceOrigin? = null
    private var isKept = false
    private var isDeleted = false

    /** How far it has been read. Read by the player's loader thread only. */
    var position = 0L
        private set

    /** How many bytes the piece had, once it has all arrived. */
    val size: Long get() = lock.withLock { written }

    /** Completed once the stream has ended: true if it arrived whole. */
    val ended = CompletableDeferred<Boolean>()

    init {
        // Blocking reads, on the pool made for them.
        scope.launch(Dispatchers.IO) { drain() }
    }

    private fun drain() {
        val buffer = ByteArray(CopyBufferBytes)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                lock.withLock {
                    // Written even when nobody reads it any more: it may still be kept.
                    writer.write(buffer, 0, read)
                    written += read
                    arrived.signalAll()
                }
            }
        } catch (e: Exception) {
            lock.withLock { failure = e }
        } finally {
            input.closeQuietly()
            val whole = lock.withLock {
                isEnded = true
                arrived.signalAll()
                try {
                    writer.close()
                } catch (_: IOException) {
                }
                if (failure == null) keepIfClaimed()
                deleteIfUnwanted()
                failure == null
            }
            ended.complete(whole)
        }
    }

    /** Says which file this piece is part of, and where: once it has all arrived, it is kept. */
    fun claim(origin: PieceOrigin) = lock.withLock {
        this.origin = origin
        if (isEnded && failure == null) keepIfClaimed()
    }

    // Under lock.
    private fun keepIfClaimed() {
        val origin = origin ?: return
        if (isKept || isDeleted) return
        isKept = true
        keep(origin.fileId, KeptPiece(ByteSpan(origin.offset, origin.offset + written), file))
    }

    // Under lock.
    private fun deleteIfUnwanted() {
        if (!isClosed || !isEnded || isKept || isDeleted) return
        isDeleted = true
        file.delete()
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
            deleteIfUnwanted()
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

/** Reads [input] to its end and throws it away, in the background. */
private fun CoroutineScope.discard(input: InputStream) {
    launch(Dispatchers.IO) {
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
