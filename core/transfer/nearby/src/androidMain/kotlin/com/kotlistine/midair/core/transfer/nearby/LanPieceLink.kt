package com.kotlistine.midair.core.transfer.nearby

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

private const val Tag = "AirLan"

/** How long connecting to the other phone's listener may take before this way counts as not there. */
private const val ConnectTimeoutMillis = 1_500

/** A token-holding client has this long to say who it is. */
private const val HelloTimeoutMillis = 3_000

/** A piece whose bytes stop coming for this long is broken. */
private const val StallTimeoutMillis = 10_000

/** Connections opened as soon as the other phone's listener is known: a lifted video asks for this many pieces at once. */
private const val WarmConnections = 4

/** Connections kept open while nothing is asked for. More than a video and a file being fetched use together. */
private const val MaxIdleConnections = 8

private const val CopyBufferBytes = 64 * 1024

/** How many bytes of a piece may wait in its connection to be sent. */
private const val PieceSendBufferBytes = 128 * 1024

/** What a phone sends of one of its shared files: [length] bytes exactly, read from [input]. */
internal class ServedPiece(val input: InputStream, val length: Long)

/** What came of asking the other phone for a piece over [LanPieceLink]. */
internal sealed interface PieceAnswer {
    /** On its way: [input] ends by itself after [length] bytes (fewer than asked for at the file's end). */
    class Coming(val input: InputStream, val length: Long) : PieceAnswer

    /** The other phone won't send it (the file isn't shared, or that part isn't readable yet). */
    data object Refused : PieceAnswer

    /** This way doesn't work (any more): ask over Nearby. */
    data object Broken : PieceAnswer
}

/**
 * The pieces of shared files over the Wi-Fi network both phones are on (the Wi-Fi Direct group Nearby opens between
 * them, or a shared network), apart from Nearby: the way a video playing on the other phone is read here.
 *
 * Over Nearby a piece costs a request frame, an answer frame and a stream payload, all on the one connection the cards
 * and every other payload share: it took 0.1 to 1.5 s to start coming. Here a piece is one request on a TCP connection
 * that is open already, answered with its length and its bytes. Several connections stay open, so pieces asked for at
 * once travel side by side and none pays for a connection being set up.
 *
 * Each phone listens ([listen]) and hands its address and a random token to the other over Nearby (encrypted), in a
 * [LinkFrame.PieceOffer]; the other connects ([connect]) and asks ([open]). A connection that doesn't open with this
 * phone's token is dropped, so nothing else on the network reads a file. What may be read is decided by [serve], the
 * same rule as over Nearby.
 */
internal class LanPieceLink(
    private val scope: CoroutineScope,
    /** The piece of this phone's shared file to send, or null to refuse. Called on a background thread; may block. */
    private val serve: (fileId: String, offset: Long, length: Long) -> ServedPiece?,
    /** [bytes] of this phone's file [fileId] went to the other phone in a piece that went whole. */
    private val onServed: (fileId: String, bytes: Long) -> Unit = { _, _ -> },
    /** Where what happens is told (logcat on a phone). */
    private val log: (String) -> Unit = { Log.i(Tag, it) },
) {
    private val random = SecureRandom()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var token: ByteArray? = null

    /** Connections the other phone opened to this one's listener. */
    private val accepted = CopyOnWriteArraySet<Socket>()

    /** The other phone's listener, once it said where it is and answered there. */
    @Volatile
    private var peer: LinkFrame.PieceOffer? = null

    /** Connections to the other phone's listener that nothing is asked on right now. */
    private val idle = ConcurrentLinkedDeque<Connection>()

    /** How many connections to the other phone's listener were opened in all (for the log, and the tests). */
    val connectionsOpened = AtomicInteger(0)

    /** Whether pieces can be asked for this way now. */
    val isUp: Boolean get() = peer != null

    /**
     * Starts listening on [address] (this phone's address on the network) and returns the offer to hand to the other
     * phone, or null when it couldn't. Blocking: call it off the main thread.
     */
    fun listen(address: String): LinkFrame.PieceOffer? {
        // A listener from before goes, with what it was sending; the way to the other phone's listener stays.
        closeListener()
        return try {
            val socket = ServerSocket()
            socket.bind(InetSocketAddress(InetAddress.getByName(address), 0), MaxIdleConnections)
            val secret = ByteArray(LanTokenBytes).also(random::nextBytes)
            server = socket
            token = secret
            scope.launch(Dispatchers.IO) { accept(socket, secret) }
            log("Listening for piece requests on $address:${socket.localPort}")
            LinkFrame.PieceOffer(address, socket.localPort, secret)
        } catch (e: IOException) {
            log("Couldn't listen for piece requests on $address: ${e.message}")
            null
        }
    }

    /**
     * Connects to the other phone's listener; from then on [open] asks there. Opens [WarmConnections] at once, so the
     * first pieces of a lifted video don't wait for a connection. Blocking: call it off the main thread.
     */
    fun connect(offer: LinkFrame.PieceOffer): Boolean {
        dropConnections()
        val first = connectTo(offer)
        if (first == null) {
            log("The other phone's piece listener doesn't answer from here: pieces stay on Nearby")
            return false
        }
        peer = offer
        idle.addFirst(first)
        repeat(WarmConnections - 1) { connectTo(offer)?.let(idle::addFirst) }
        log("Pieces go over Wi-Fi to ${offer.address}:${offer.port} (${idle.size} connections open)")
        return true
    }

    /**
     * Asks the other phone for [length] bytes of its shared file [fileId] at [offset]. Blocking until its answer
     * starts: call it off the main thread. The stream of a [PieceAnswer.Coming] must be read to its end or closed.
     */
    fun open(fileId: String, offset: Long, length: Long): PieceAnswer {
        val offer = peer ?: return PieceAnswer.Broken
        // A connection that sat idle may have gone meanwhile (the listener moved to another network): one more try on
        // a new one says whether this way is still there.
        for (attempt in 0..1) {
            val connection = (if (attempt == 0) idle.pollFirst() else null) ?: connectTo(offer) ?: break
            try {
                connection.output.writeLong(offset)
                connection.output.writeLong(length)
                connection.output.writeUTF(fileId)
                connection.output.flush()
                val coming = connection.input.readLong()
                if (coming < 0) {
                    release(connection)
                    return PieceAnswer.Refused
                }
                if (coming > length) throw IOException("a piece of $coming bytes, asked for $length")
                return PieceAnswer.Coming(PieceInput(connection, coming), coming)
            } catch (_: IOException) {
                connection.close()
            }
        }
        if (peer === offer) {
            log("Pieces over Wi-Fi broke: back on Nearby")
            peer = null
            dropConnections()
        }
        return PieceAnswer.Broken
    }

    /** Stops listening and closes every connection, both ways. */
    fun close() {
        peer = null
        dropConnections()
        closeListener()
    }

    private fun closeListener() {
        token = null
        server?.closeQuietly()
        server = null
        accepted.forEach { it.closeQuietly() }
        accepted.clear()
    }

    private fun dropConnections() {
        while (true) (idle.pollFirst() ?: break).close()
    }

    private fun connectTo(offer: LinkFrame.PieceOffer): Connection? = try {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.soTimeout = StallTimeoutMillis
        socket.connect(InetSocketAddress(InetAddress.getByName(offer.address), offer.port), ConnectTimeoutMillis)
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        output.write(offer.token)
        output.flush()
        connectionsOpened.incrementAndGet()
        Connection(socket, DataInputStream(socket.getInputStream().buffered(CopyBufferBytes)), output)
    } catch (_: IOException) {
        null
    }

    /** [connection] has nothing on its way any more: the next piece may be asked for on it. */
    private fun release(connection: Connection) {
        if (peer == null || idle.size >= MaxIdleConnections) connection.close() else idle.addFirst(connection)
    }

    private fun accept(socket: ServerSocket, secret: ByteArray) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            scope.launch(Dispatchers.IO) { answer(client, secret) }
        }
    }

    /** Answers the requests on one connection, one after the other, until it closes. */
    private fun answer(client: Socket, secret: ByteArray) {
        try {
            client.tcpNoDelay = true
            // A short queue: what waits here to be sent waits in front of the card messages at the radio too.
            client.sendBufferSize = PieceSendBufferBytes
            client.soTimeout = HelloTimeoutMillis
            val input = DataInputStream(client.getInputStream().buffered())
            val hello = ByteArray(LanTokenBytes)
            input.readFully(hello)
            if (token !== secret || !MessageDigest.isEqual(hello, secret)) {
                log("Dropped a piece connection from ${client.inetAddress.hostAddress}: not the paired phone")
                return
            }
            // The paired phone: its requests may be minutes apart from here on.
            client.soTimeout = 0
            accepted += client
            val output = DataOutputStream(client.getOutputStream().buffered(CopyBufferBytes))
            val buffer = ByteArray(CopyBufferBytes)
            while (true) {
                val offset = input.readLong()
                val length = input.readLong()
                val fileId = input.readUTF()
                if (offset < 0 || length !in 1..MaxRangeBytes || fileId.isEmpty() || fileId.length > MaxFileIdBytes) {
                    throw IOException("a request for $length bytes at $offset")
                }
                val piece = serve(fileId, offset, length)
                if (piece == null) {
                    output.writeLong(-1)
                    output.flush()
                    continue
                }
                piece.input.use { bytes ->
                    output.writeLong(piece.length)
                    var left = piece.length
                    while (left > 0) {
                        val read = bytes.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        // Shorter than promised: the asker must not take what follows for the rest of it.
                        if (read < 0) throw IOException("$fileId ended ${left} bytes early")
                        output.write(buffer, 0, read)
                        left -= read
                    }
                    output.flush()
                }
                onServed(fileId, piece.length)
            }
        } catch (_: IOException) {
            // The asker closed it (it wanted no more of a piece, or the link went): nothing to do.
        } finally {
            accepted -= client
            client.closeQuietly()
        }
    }

    private class Connection(val socket: Socket, val input: DataInputStream, val output: DataOutputStream) {
        fun close() = socket.closeQuietly()
    }

    /**
     * A piece arriving on [connection]: ends after [length] bytes, and gives the connection back for the next piece
     * then. Closed before its end, it closes the connection: the rest of the piece stops coming.
     */
    private inner class PieceInput(private val connection: Connection, length: Long) : InputStream() {
        private var left = length
        private var isDone = false

        init {
            if (left == 0L) done()
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            val read = try {
                connection.input.read(buffer, offset, minOf(length.toLong(), left).toInt())
            } catch (e: IOException) {
                close()
                throw e
            }
            if (read < 0) {
                close()
                throw IOException("The piece ended $left bytes early")
            }
            left -= read
            if (left == 0L) done()
            return read
        }

        private fun done() {
            if (isDone) return
            isDone = true
            release(connection)
        }

        override fun close() {
            if (isDone) return
            isDone = true
            connection.close()
        }
    }
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
