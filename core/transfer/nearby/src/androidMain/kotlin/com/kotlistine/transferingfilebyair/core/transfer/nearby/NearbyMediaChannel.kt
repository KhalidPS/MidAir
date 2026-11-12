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
import kotlinx.coroutines.Job
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

/**
 * The first piece a player asks for where it starts reading, when pieces travel over Wi-Fi on connections of their own
 * ([LanPieceLink]). A player starting a video looks at several places before it settles (12 to 13 pieces before its
 * first frame, seen on the phones), and every piece is sent whole: a small first one costs a glance a quarter of the
 * bytes. Over Nearby a piece's round trip costs more than its bytes, so there the first piece is a whole one.
 */
private const val FirstWifiPieceBytes = 256L * 1024

/** Rounds of pieces in a row that may bring nothing new before a fetch gives up. */
private const val FetchRetries = 3

/**
 * The longest a fetch holds back for the pieces a player is waiting for. Usually far less: a player whose buffer is
 * full asks for nothing for seconds. The limit keeps a fetch going on a link too slow to ever fill that buffer.
 */
private const val PlayerFirstMillis = 2_000L

/** How long the other phone has to hash its whole file (about 5 s for 1 GB on a mid-range phone). */
private const val DigestTimeoutMillis = 60_000L

/**
 * The longest a fetch holds back for a card in the air. A card is in the air for a second or two (a held one at most
 * 12 s); this only keeps a fetch from waiting forever on a hold that was never let go.
 */
private const val CardHoldMaxMillis = 15_000L

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

    /**
     * The pieces' own way over the Wi-Fi network both phones are on (the Wi-Fi Direct group, or a shared network),
     * apart from Nearby: asked there first, once the other phone said where it listens (see [connectPieces]).
     */
    private val pieces = LanPieceLink(
        scope,
        serve = ::servePiece,
        onServed = { fileId, bytes -> served[fileId]?.addAndGet(bytes) },
    )

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

    /**
     * Files of this phone the other may read only the header and index of (see [shareIndex]): for each, the parts kept
     * closed (its pictures and sound), once found. Null: not MP4 as far as can be told, so nothing of it is read.
     */
    private val closedParts = ConcurrentHashMap<String, Deferred<List<ByteSpan>?>>()

    /** How long the other phone's files are, by file id, where it said so ahead of a card (see [prefetch]). */
    private val sizes = ConcurrentHashMap<String, Long>()

    /** Fetches of a file's index running ahead of its card, by file id. */
    private val prefetching = ConcurrentHashMap<String, Job>()

    /** Digests this phone asked for, by request id, completed with the SHA-256 (null: refused or the link went). */
    private val digests = ConcurrentHashMap<Long, CompletableDeferred<ByteArray?>>()

    /** Pieces asked for by open streams (players) that haven't all arrived yet: a fetch holds back while there are. */
    private val playerPieces = MutableStateFlow(0)

    /** A card is in the air: fetches ask for no new pieces (see [holdFetches]). */
    private val cardInAir = MutableStateFlow(false)

    /** Which shared file each piece being sent belongs to, by payload id. */
    private val sendingFile = ConcurrentHashMap<Long, String>()

    /** Bytes of each shared file sent in whole pieces, by file id. */
    private val served = ConcurrentHashMap<String, AtomicLong>()

    // Bluetooth is LOW (BLE) or MEDIUM (classic): far too slow for a phone's video. Wi-Fi is HIGH.
    override val isFast: Boolean get() = quality >= BandwidthInfo.Quality.HIGH

    private val fastUpdates = MutableStateFlow(false)
    override val isFastUpdates: StateFlow<Boolean> = fastUpdates.asStateFlow()

    override fun share(fileId: String, uri: String): String? {
        shared[fileId] = uri
        closedParts.remove(fileId)
        served[fileId] = AtomicLong(0)
        return lan.share(fileId, uri)
    }

    override fun shareIndex(fileId: String, uri: String, sizeBytes: Long) {
        // Readable in full already (its card is in the air): it stays so.
        if (shared.containsKey(fileId) && !closedParts.containsKey(fileId)) return
        // Found off the main thread; a piece asked for meanwhile waits for it.
        closedParts[fileId] = scope.async(Dispatchers.IO) { mp4MediaSpans(sizeBytes) { headerOf(uri, it) } }
        shared[fileId] = uri
    }

    /** Up to [Mp4BoxHeaderBytes] bytes of the file at [uri] from [offset]; null when it can't be read there. */
    private fun headerOf(uri: String, offset: Long): ByteArray? = try {
        openAt(uri, offset, Mp4BoxHeaderBytes.toLong())?.use { input ->
            val bytes = ByteArray(Mp4BoxHeaderBytes)
            var read = 0
            while (read < bytes.size) {
                val more = input.read(bytes, read, bytes.size - read)
                if (more < 0) break
                read += more
            }
            if (read == 0) null else bytes.copyOf(read)
        }
    } catch (_: IOException) {
        null
    }

    override fun unshare(fileId: String) {
        shared.remove(fileId)
        closedParts.remove(fileId)
        served.remove(fileId)
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
        // Fetched ahead of the card (the file's index, see prefetch) or read before: no round trip.
        keptPart(fileId, offset)?.let { return Result.Success(PiecedStream(fileId, offset, it, offset + it.size)) }
        val firstBytes = if (pieces.isUp) FirstWifiPieceBytes else PieceBytes
        val first = openPiece(fileId, offset, firstBytes, forPlayer = true)
            ?: return Result.Failure(DataError.Network.DISCONNECTED)
        return Result.Success(PiecedStream(fileId, offset, first, offset + firstBytes, fillAfter = firstBytes / 2))
    }

    /**
     * The kept bytes of [fileId] from [offset] to the end of the kept piece they are in; null when none are kept there,
     * or the file's size isn't known (without it nothing says where a kept piece ends the file).
     */
    private fun keptPart(fileId: String, offset: Long): PiecePart? {
        if (!sizes.containsKey(fileId)) return null
        val piece = keptOf(fileId).firstOrNull { offset >= it.span.start && offset < it.span.end } ?: return null
        return try {
            KeptPart(piece.file, skip = offset - piece.span.start, size = piece.span.end - offset)
        } catch (_: IOException) {
            // Let go of meanwhile.
            null
        }
    }

    override fun prefetch(fileId: String, sizeBytes: Long) {
        sizes[fileId] = sizeBytes
        if (local.containsKey(fileId) || prefetching[fileId]?.isActive == true) return
        prefetching[fileId] = scope.launch {
            val startedAt = System.currentTimeMillis()
            // What a player reads before it can show a frame: the start of each of the file's boxes, up to the one
            // with the pictures, and all of its index, wherever that is and however long (a phone's recording keeps
            // it at the end, about 0.3 MB a minute; a downloaded video often at the start). Read at the lift, they
            // cost 1.2 to 3.2 s of round trips.
            var offset = 0L
            var boxes = 0
            var hasIndex = false
            var sawPictures = false
            while (offset < sizeBytes && !(hasIndex && sawPictures) && ++boxes <= MaxMp4Boxes) {
                val box = headerAhead(fileId, offset, sizeBytes)?.let { mp4BoxAt(offset, it, sizeBytes) } ?: break
                when (box.type) {
                    "moov" -> {
                        if (!readAhead(fileId, ByteSpan(box.start, box.end))) break
                        hasIndex = true
                    }
                    "mdat" -> sawPictures = true
                }
                offset = box.end
            }
            val millis = System.currentTimeMillis() - startedAt
            if (hasIndex) {
                Log.i(Tag, "Index of $fileId fetched ahead in $millis ms")
            } else {
                // Not MP4, or the other phone stopped sharing it: its card starts as it did before.
                Log.i(Tag, "Index of $fileId not found ahead ($boxes boxes read, $millis ms)")
            }
        }
    }

    /**
     * The first bytes of the box at [offset] of the other phone's file [fileId], for a look at what it is: from what is
     * kept, or read ahead now. Null when they don't come.
     */
    private suspend fun headerAhead(fileId: String, offset: Long, sizeBytes: Long): ByteArray? {
        val wanted = minOf(Mp4BoxHeaderBytes.toLong(), sizeBytes - offset).toInt()
        keptBytes(fileId, offset, wanted)?.let { return it }
        if (pieceAhead(fileId, offset, minOf(PieceBytes, sizeBytes - offset)) < wanted) return null
        return keptBytes(fileId, offset, wanted)
    }

    /** Reads ahead whatever of [span] of [fileId] isn't kept yet, a piece at a time; false if a piece didn't come. */
    private suspend fun readAhead(fileId: String, span: ByteSpan): Boolean {
        var position = span.start
        while (position < span.end) {
            val here = keptOf(fileId).firstOrNull { position >= it.span.start && position < it.span.end }
            if (here != null) {
                position = here.span.end
                continue
            }
            val got = pieceAhead(fileId, position, minOf(PieceBytes, span.end - position))
            if (got <= 0) return false
            position += got
        }
        return true
    }

    /**
     * Asks for [length] bytes of [fileId] at [offset] ahead of its card, and waits until they are kept. Returns how many
     * came: fewer than asked for when the other phone lets only so much be read yet, 0 when none did.
     */
    private suspend fun pieceAhead(fileId: String, offset: Long, length: Long): Long {
        // Never in the way of a card's moves or of a video that plays now.
        withTimeoutOrNull(CardHoldMaxMillis) { cardInAir.first { !it } }
        withTimeoutOrNull(PlayerFirstMillis) { playerPieces.first { it <= 0 } }
        val piece = openPiece(fileId, offset, length, forPlayer = false) ?: return 0
        // Kept as it ends (claimed when it arrived).
        val whole = piece.ended.await()
        val size = piece.size
        piece.close()
        return if (whole) size else 0
    }

    /** [length] kept bytes of [fileId] at [offset]; null unless one kept piece has them all. */
    private suspend fun keptBytes(fileId: String, offset: Long, length: Int): ByteArray? {
        val piece = keptOf(fileId).firstOrNull { offset >= it.span.start && offset + length <= it.span.end } ?: return null
        return withContext(Dispatchers.IO) {
            try {
                RandomAccessFile(piece.file, "r").use { file ->
                    file.seek(offset - piece.span.start)
                    ByteArray(length).also(file::readFully)
                }
            } catch (_: IOException) {
                // Let go of meanwhile.
                null
            }
        }
    }

    /**
     * Asks for [length] bytes of [fileId] at [offset]; null if they don't come. The piece is already draining when they
     * do. [forPlayer] pieces count in [playerPieces] until they have all arrived.
     */
    private suspend fun openPiece(fileId: String, offset: Long, length: Long, forPlayer: Boolean): DrainedPiece? {
        if (forPlayer) playerPieces.update { it + 1 }
        var piece: DrainedPiece? = null
        var requestId: Long? = null
        try {
            val askedAt = System.currentTimeMillis()
            // Over Wi-Fi first, on a connection of its own; over Nearby when that way isn't there (or just broke).
            when (val overWifi = if (pieces.isUp) withContext(Dispatchers.IO) { pieces.open(fileId, offset, length) } else PieceAnswer.Broken) {
                is PieceAnswer.Coming -> {
                    piece = try {
                        DrainedPiece(overWifi.input, File(piecesDir, "piece-w${nextRequestId.getAndIncrement()}.part"), scope, ::keepPiece)
                    } catch (_: IOException) {
                        overWifi.input.closeQuietly()
                        null
                    }
                    piece?.claim(PieceOrigin(fileId, offset, length))
                    Log.i(Tag, "Piece of $fileId at $offset opened in ${System.currentTimeMillis() - askedAt} ms over Wi-Fi")
                    return piece
                }
                PieceAnswer.Refused -> {
                    Log.w(Tag, "Piece of $fileId at $offset refused by the other phone")
                    return null
                }
                PieceAnswer.Broken -> Unit
            }
            val id = nextRequestId.getAndIncrement()
            requestId = id
            val answer = CompletableDeferred<DrainedPiece?>()
            requests[id] = answer
            origins[id] = PieceOrigin(fileId, offset, length)
            val asked = withContext(Dispatchers.Main.immediate) {
                val endpoint = endpoint() ?: return@withContext false
                val request = LinkFrame.RangeRequest(id, offset, length, fileId)
                client.sendPayload(endpoint, Payload.fromBytes(request.encode()))
                true
            }
            piece = if (asked) withTimeoutOrNull(OpenTimeoutMillis) { answer.await() } else null
            if (piece == null) {
                // It may still turn up after the wait: it is kept then, but nobody reads it (see deliver).
                Log.w(Tag, "Piece of $fileId at $offset: no answer (asked: $asked)")
                if (!asked) origins.remove(id)
                return null
            }
            Log.i(Tag, "Piece of $fileId at $offset opened in ${System.currentTimeMillis() - askedAt} ms")
            return piece
        } finally {
            requestId?.let(requests::remove)
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
            // So does a card in the air: its moves share the connection.
            if (cardInAir.value) {
                val heldAt = System.currentTimeMillis()
                withTimeoutOrNull(CardHoldMaxMillis) { cardInAir.first { !it } }
                Log.i(Tag, "Fetching $fileId: held ${System.currentTimeMillis() - heldAt} ms for a card in the air")
            }
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
        prefetching.remove(fileId)?.cancel()
        sizes.remove(fileId)
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

    override fun holdFetches(hold: Boolean) {
        cardInAir.value = hold
    }

    override fun servedBytes(fileId: String): Long = served[fileId]?.get() ?: 0L

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
                val fileId = sendingFile.remove(update.payloadId)
                if (fileId != null && update.status == PayloadTransferUpdate.Status.SUCCESS) {
                    served[fileId]?.addAndGet(update.bytesTransferred)
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
        sendingFile.clear()
        served.clear()
        cardInAir.value = false
        origins.clear()
        digests.values.forEach { it.complete(null) }
        shared.clear()
        closedParts.clear()
        lan.unshareAll()
        pieces.close()
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
        val closed = closedParts[request.fileId]
        scope.launch {
            // Before its card is lifted, only what isn't pictures and sound is read: cut short where those start.
            val span = if (closed == null) {
                ByteSpan(request.offset, request.offset + request.length)
            } else {
                closed.await()?.let { openSpan(request.offset, request.length, it) }
            }
            if (span == null) Log.w(Tag, "Piece of ${request.fileId} at ${request.offset} refused: its card isn't lifted")
            val input = span?.let { withContext(Dispatchers.IO) { openAt(uri, it.start, it.length) } }
            if (input == null || endpoint() != endpoint) {
                input?.closeQuietly()
                client.sendPayload(endpoint, Payload.fromBytes(LinkFrame.RangeRefused(request.requestId).encode()))
                return@launch
            }
            val payload = Payload.fromStream(input)
            sending[payload.id] = input
            sendingFile[payload.id] = request.fileId
            client.sendPayload(endpoint, Payload.fromBytes(LinkFrame.RangeOpened(request.requestId, payload.id).encode()))
            client.sendPayload(endpoint, payload).addOnFailureListener { e ->
                Log.w(Tag, "Piece ${payload.id} couldn't be sent", e)
                sending.remove(payload.id)
                sendingFile.remove(payload.id)
                input.closeQuietly()
            }
        }
    }

    /**
     * Starts listening for the other phone's piece requests on [address], this phone's address on the network both
     * are on; returns the offer to hand to it, or null when it couldn't. Blocking: call it off the main thread.
     */
    fun listenForPieces(address: String): LinkFrame.PieceOffer? = pieces.listen(address)

    /**
     * The other phone listens for piece requests at [offer]: asked there from now on, if it answers (false when it
     * doesn't). Blocking.
     */
    fun connectPieces(offer: LinkFrame.PieceOffer): Boolean = pieces.connect(offer)

    /** Whether pieces are asked for over Wi-Fi now. */
    val arePiecesUp: Boolean get() = pieces.isUp

    /**
     * What to send of [length] bytes of the shared file [fileId] at [offset], asked for over [pieces]; null to refuse.
     * The same rule as [serve]: only a shared file, and before its card is lifted only up to its pictures and sound.
     * Blocking, on the piece connection's thread.
     */
    private fun servePiece(fileId: String, offset: Long, length: Long): ServedPiece? {
        val uri = shared[fileId]
        if (uri == null) {
            Log.w(Tag, "Piece of $fileId refused: not shared")
            return null
        }
        val closed = closedParts[fileId]
        val span = if (closed == null) {
            ByteSpan(offset, offset + length)
        } else {
            runBlocking { closed.await() }?.let { openSpan(offset, length, it) }
        }
        if (span == null) {
            Log.w(Tag, "Piece of $fileId at $offset refused: its card isn't lifted")
            return null
        }
        return try {
            val descriptor = appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
            // Its length goes ahead of its bytes: only as much as the file has there.
            val end = minOf(span.end, descriptor.statSize)
            if (end <= span.start) {
                descriptor.close()
                return null
            }
            val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor).apply { channel.position(span.start) }
            ServedPiece(LimitedInputStream(input, end - span.start), end - span.start)
        } catch (_: Exception) {
            null
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
        first: PiecePart,
        /** Where [first] ends in the file. */
        firstEnd: Long,
        /** How far into the current piece the pieces ahead are asked for: half of it. */
        private var fillAfter: Long = PieceBytes / 2,
    ) : ByteStream {
        private var current = first

        /** Where [current] starts in the file. */
        private var currentStart = start

        /** The rest of the file from this phone's copy, once there is one. */
        private var fromHere: InputStream? = null

        /** Parts after [current], in file order: pieces asked for, and ones kept from before. */
        private val ahead = ArrayDeque<Deferred<PiecePart?>>()

        /** Where the next part to line up starts. */
        private var nextOffset = firstEnd

        @Volatile
        private var isClosed = false

        /** Whether [position] is at or past the file's end, when its size is known (see [prefetch]). */
        private fun isPastEnd(position: Long): Boolean = sizes[fileId]?.let { position >= it } == true

        private fun fillAhead() {
            // On this phone already: the next piece comes from here.
            if (local.containsKey(fileId)) return
            while (ahead.size < PiecesAhead) {
                val offset = nextOffset
                if (isPastEnd(offset)) return
                val here = keptPart(fileId, offset)
                if (here != null) {
                    ahead.addLast(CompletableDeferred<PiecePart?>(here))
                    nextOffset = offset + here.size
                } else {
                    ahead.addLast(scope.async { openPiece(fileId, offset, PieceBytes, forPlayer = true) })
                    nextOffset += PieceBytes
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (isClosed) throw IOException("Range closed")
                fromHere?.let { return it.read(buffer, offset, length) }
                val read = current.read(buffer, offset, length)
                if (current.position >= fillAfter) fillAhead()
                if (read >= 0) return read
                val next = currentStart + current.size
                if (current.isLast || isPastEnd(next)) return -1
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
                fillAfter = PieceBytes / 2
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

/** Which file a piece belongs to, where in it it starts, and how many bytes were asked for. */
private class PieceOrigin(val fileId: String, val offset: Long, val length: Long)

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
) : PiecePart {
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
    override var position = 0L
        private set

    /** How many bytes the piece had, once it has all arrived. */
    override val size: Long get() = lock.withLock { written }

    /** One that came shorter than was asked for is the file's last. */
    override val isLast: Boolean get() = lock.withLock { written < (origin?.length ?: PieceBytes) }

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
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
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

    override fun close() {
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

/** A part of a file a player reads through: a piece arriving over the link, or one kept from before. */
private interface PiecePart {
    /** How far it has been read. */
    val position: Long

    /** How many bytes it has in all; known once it has been read to its end. */
    val size: Long

    /** Whether the file ends with it, as far as the part itself can tell. */
    val isLast: Boolean

    /** Like [InputStream.read]. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

/** The [size] bytes at [skip] of a piece kept in [file]: here already, so reading them never waits on the link. */
private class KeptPart(file: File, skip: Long, override val size: Long) : PiecePart {
    private val reader = RandomAccessFile(file, "r").apply { seek(skip) }

    override var position = 0L
        private set

    // A kept piece can be any length (a gap a fetch filled): only the file's size says where it ends.
    override val isLast: Boolean get() = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val left = size - position
        if (left <= 0) return -1
        val read = reader.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (read > 0) position += read
        return read
    }

    override fun close() {
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
