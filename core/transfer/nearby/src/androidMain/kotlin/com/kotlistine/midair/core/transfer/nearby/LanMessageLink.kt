package com.kotlistine.midair.core.transfer.nearby

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

private const val Tag = "AirLan"

/** How long connecting to the other phone's listener may take before the Wi-Fi way counts as not there. */
private const val ConnectTimeoutMillis = 1_500

/** A token-holding client has this long to say who it is. */
private const val HelloTimeoutMillis = 3_000

/** The largest message: a card's start carries its picture, which is far smaller. Anything bigger is not ours. */
private const val MaxMessageBytes = 1024 * 1024

/** Sends slower than this are logged: on a shared Wi-Fi they should take a few milliseconds. */
private const val SlowSendMillis = 100L

/**
 * The traffic class of the card messages' connection: "expedited forwarding", which Wi-Fi sends from its voice
 * queue, ahead of a video's bytes waiting in the same phone's radio.
 */
private const val CardTrafficClass = 0xB8

/**
 * The cards' messages over the Wi-Fi network both phones are on, apart from Nearby. Nearby carries the files and the
 * card messages on one connection with no priorities, so while a file moved the messages waited behind its bytes for
 * seconds. Here they have a TCP connection of their own.
 *
 * Each phone listens ([listen]) and hands its address and a random token to the other over Nearby (encrypted), in a
 * [LinkFrame.LanOffer]. Each then connects to the other's listener ([connect]) for what it sends: one connection each
 * way. A connection that doesn't open with this phone's token is dropped, so nothing else on the network gets in.
 *
 * [onMessage] hears every message that arrives, on a background thread, in order.
 */
internal class LanMessageLink(
    private val scope: CoroutineScope,
    private val onMessage: (ByteArray) -> Unit,
    /** Where what happens is told (logcat on a phone). */
    private val log: (String) -> Unit = { Log.i(Tag, it) },
) {
    private val random = SecureRandom()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var token: ByteArray? = null

    /** To the other phone's listener, for what this phone sends. */
    @Volatile
    private var outgoing: DataOutputStream? = null

    @Volatile
    private var outgoingSocket: Socket? = null

    /** From the other phone, accepted by this phone's listener. */
    @Volatile
    private var incomingSocket: Socket? = null

    private val sendLock = Mutex()

    /** Whether messages go this way now. */
    val isUp: Boolean get() = outgoing != null

    /**
     * Starts listening on [address] (this phone's Wi-Fi address) and returns the offer to hand to the other phone, or
     * null when it couldn't. Blocking: call it off the main thread.
     */
    fun listen(address: String): LinkFrame.LanOffer? {
        // A listener from before goes; the way out to the other phone (which may be up already) stays.
        server?.closeQuietly()
        incomingSocket?.closeQuietly()
        return try {
            val socket = ServerSocket()
            socket.bind(InetSocketAddress(InetAddress.getByName(address), 0), 1)
            val secret = ByteArray(LanTokenBytes).also(random::nextBytes)
            server = socket
            token = secret
            scope.launch(Dispatchers.IO) { accept(socket, secret) }
            log("Listening for card messages on $address:${socket.localPort}")
            LinkFrame.LanOffer(address, socket.localPort, secret)
        } catch (e: IOException) {
            log("Couldn't listen for card messages on $address: ${e.message}")
            null
        }
    }

    /** Connects to the other phone's listener; from then on [send] goes this way. Blocking: call it off the main thread. */
    fun connect(offer: LinkFrame.LanOffer): Boolean = try {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.trafficClass = CardTrafficClass
        socket.connect(InetSocketAddress(InetAddress.getByName(offer.address), offer.port), ConnectTimeoutMillis)
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        output.write(offer.token)
        output.flush()
        outgoingSocket?.closeQuietly()
        outgoingSocket = socket
        outgoing = output
        log("Card messages go over Wi-Fi to ${offer.address}:${offer.port}")
        true
    } catch (e: IOException) {
        log("The other phone's listener doesn't answer from here (${e.message}): card messages stay on Nearby")
        false
    }

    /** Sends [bytes] this way; false when it isn't up (or just broke), so the caller sends them the other way. */
    suspend fun send(bytes: ByteArray): Boolean {
        if (outgoing == null) return false
        return sendLock.withLock {
            val output = outgoing ?: return@withLock false
            withContext(Dispatchers.IO) {
                val startedAt = System.currentTimeMillis()
                try {
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                    val millis = System.currentTimeMillis() - startedAt
                    if (millis > SlowSendMillis) log("A card message took $millis ms over Wi-Fi")
                    true
                } catch (e: IOException) {
                    log("Card messages over Wi-Fi broke (${e.message}): back on Nearby")
                    outgoing = null
                    outgoingSocket?.closeQuietly()
                    outgoingSocket = null
                    false
                }
            }
        }
    }

    /** Stops listening and closes both ways. */
    fun close() {
        outgoing = null
        token = null
        outgoingSocket?.closeQuietly()
        outgoingSocket = null
        incomingSocket?.closeQuietly()
        incomingSocket = null
        server?.closeQuietly()
        server = null
    }

    private fun accept(socket: ServerSocket, secret: ByteArray) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            scope.launch(Dispatchers.IO) { read(client, secret) }
        }
    }

    private fun read(client: Socket, secret: ByteArray) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = HelloTimeoutMillis
            val input = DataInputStream(client.getInputStream().buffered())
            val hello = ByteArray(LanTokenBytes)
            input.readFully(hello)
            if (token !== secret || !MessageDigest.isEqual(hello, secret)) {
                log("Dropped a connection from ${client.inetAddress.hostAddress}: not the paired phone")
                client.closeQuietly()
                return
            }
            // The paired phone: messages may be seconds apart from here on.
            client.soTimeout = 0
            incomingSocket?.closeQuietly()
            incomingSocket = client
            while (true) {
                val size = input.readInt()
                if (size !in 1..MaxMessageBytes) throw IOException("a message of $size bytes")
                val bytes = ByteArray(size)
                input.readFully(bytes)
                onMessage(bytes)
            }
        } catch (e: IOException) {
            if (incomingSocket === client) log("Card messages from the other phone over Wi-Fi ended: ${e.message}")
            client.closeQuietly()
        }
    }
}

private fun java.io.Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
