package com.kotlistine.transferingfilebyair.core.transfer.lan

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/** A hello is a few hundred bytes; anything bigger before the encryption is up is not ours. */
private const val MaxHelloBytes = 4 * 1024

/** The GCM tag every sealed frame carries on top of its body. */
private const val SealOverheadBytes = 16

/** This device, as it introduces itself. */
internal class LanIdentity(val deviceId: ByteArray, val name: String)

/**
 * One encrypted TCP connection to the other device. [open] runs the key exchange: each end sends a [LanHello] with a
 * fresh P-256 public key, and both derive, from the shared secret and the two hellos ([transcript]), a key for each
 * direction, the [code] the users compare, and the [sessionSecret] the files' connection proves itself with.
 *
 * A device in the middle would have to run a key exchange with each end, and the two would get different codes.
 *
 * [send] may be called from any thread; [receive] from one reader at a time. Both block.
 */
internal class SecureChannel private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sealing: FrameCipher,
    private val opening: FrameCipher,
    /** What the other end said about itself. */
    val peer: LanHello,
    /** Hash of both hellos, in the dialer's order: what every proof on this connection is bound to. */
    val transcript: ByteArray,
    val code: String,
    val sessionSecret: ByteArray,
) {
    private val sendLock = Any()

    val peerId: ByteArray get() = peer.deviceId

    /** The other end's address, as this connection reached it. */
    val remoteHost: String get() = socket.inetAddress.hostAddress.orEmpty()

    @Throws(IOException::class)
    fun send(frame: LanFrame) {
        val plain = frame.encode()
        synchronized(sendLock) {
            val sealed = sealing.apply(plain)
            output.writeInt(sealed.size)
            output.write(sealed)
            output.flush()
        }
    }

    /**
     * The next frame; null for one this version doesn't know (it opened, so it comes from the other end: a newer build,
     * skipped rather than ending the link). An [IOException] when the connection ended, timed out, or was tampered with.
     */
    @Throws(IOException::class)
    fun receive(): LanFrame? {
        val size = input.readInt()
        if (size <= 0 || size > MaxFrameBytes + SealOverheadBytes) throw IOException("Frame of $size bytes")
        val sealed = ByteArray(size).also(input::readFully)
        val plain = try {
            opening.apply(sealed)
        } catch (e: Exception) {
            throw IOException("Frame failed to open", e)
        }
        return decodeLanFrame(plain)
    }

    /** How long [receive] may wait for a frame before the connection counts as gone (0: forever). */
    fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    /** Proof that this end holds [key], bound to this connection: no use on any other. */
    fun proof(key: ByteArray, senderId: ByteArray): ByteArray = LanCrypto.hmac(key, transcript, senderId)

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /**
         * Runs the key exchange on [socket] (blocking). [isDialer]: this end opened the connection, and says what it is
         * for ([purpose]); the listening end answers with the same. Throws [IOException] on anything unexpected.
         */
        @Throws(IOException::class)
        fun open(socket: Socket, identity: LanIdentity, purpose: Purpose?, isDialer: Boolean): SecureChannel {
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val keys = LanCrypto.newKeyPair()

            fun sendHello(forPurpose: Purpose): ByteArray {
                val hello = LanHello(
                    version = LanProtocolVersion,
                    purpose = forPurpose,
                    deviceId = identity.deviceId,
                    name = identity.name,
                    publicKey = keys.public.encoded,
                    nonce = LanCrypto.random(NonceBytes),
                ).encode()
                output.writeInt(hello.size)
                output.write(hello)
                output.flush()
                return hello
            }

            fun receiveHello(): Pair<ByteArray, LanHello> {
                val size = input.readInt()
                if (size <= 0 || size > MaxHelloBytes) throw IOException("Hello of $size bytes")
                val bytes = ByteArray(size).also(input::readFully)
                val hello = LanHello.decode(bytes) ?: throw IOException("Unreadable hello")
                if (hello.version != LanProtocolVersion) throw IOException("Protocol ${hello.version}, not ours")
                return bytes to hello
            }

            val (dialerHello, listenerHello, peer) = if (isDialer) {
                val mine = sendHello(requireNotNull(purpose))
                val (theirs, peer) = receiveHello()
                if (peer.purpose != purpose) throw IOException("Answered for ${peer.purpose}")
                Triple(mine, theirs, peer)
            } else {
                val (theirs, peer) = receiveHello()
                val mine = sendHello(peer.purpose)
                Triple(theirs, mine, peer)
            }
            if (peer.deviceId.contentEquals(identity.deviceId)) throw IOException("Connected to itself")

            val transcript = LanCrypto.sha256(dialerHello, listenerHello)
            val secret = LanCrypto.agree(keys, peer.publicKey)
            val dialerToListener = LanCrypto.deriveKey(secret, transcript, "air dialer to listener")
            val listenerToDialer = LanCrypto.deriveKey(secret, transcript, "air listener to dialer")
            return SecureChannel(
                socket = socket,
                input = input,
                output = output,
                sealing = FrameCipher.sealing(if (isDialer) dialerToListener else listenerToDialer),
                opening = FrameCipher.opening(if (isDialer) listenerToDialer else dialerToListener),
                peer = peer,
                transcript = transcript,
                code = pairingCode(LanCrypto.deriveKey(secret, transcript, "air pairing code")),
                sessionSecret = LanCrypto.deriveKey(secret, transcript, "air session"),
            )
        }
    }
}
