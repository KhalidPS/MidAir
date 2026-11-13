package com.kotlistine.transferingfilebyair.core.transfer.lan

/** Bumped when the frames change in a way an older build can't read; a mismatch ends the connection. */
internal const val LanProtocolVersion = 1

/** Random id of an app install: who a device is, across names and addresses. */
internal const val DeviceIdBytes = 16
internal const val NonceBytes = 16

/** A frame's body may not be bigger: the largest is a file chunk (64 KB) or a card message (32 KB). */
internal const val MaxFrameBytes = 256 * 1024

/** What a connection carries. Each paired link has one of each. */
internal enum class Purpose(val code: Int) {
    /** Pairing, card messages and the files' bookkeeping: small frames, sent at once. */
    CONTROL(1),

    /** The bytes of files, apart, so a big file never holds up a card's moves. */
    FILES(2),
    ;

    companion object {
        fun of(code: Int): Purpose? = entries.firstOrNull { it.code == code }
    }
}

/**
 * How one end shows who it is, once the connection is encrypted. Both ends send one; see [decideAuth].
 */
internal enum class AuthMode(val code: Int) {
    /** A first meeting: nothing to prove, the users compare the code on both screens. */
    PAIR(0),

    /** A device paired before: proves it holds the key they agreed on then. */
    TRUSTED(1),

    /** The phone scanned the laptop's QR code: both prove they know the one-time secret it held. */
    QR(2),

    /** The files' connection of a link already up: proves it belongs to that link. */
    SESSION(3),
    ;

    companion object {
        fun of(code: Int): AuthMode? = entries.firstOrNull { it.code == code }
    }
}

/** The first thing each end sends, in the clear: who it is, and its half of the key exchange. */
internal class LanHello(
    val version: Int,
    val purpose: Purpose,
    val deviceId: ByteArray,
    val name: String,
    val publicKey: ByteArray,
    val nonce: ByteArray,
) {
    fun encode(): ByteArray = ByteWriter().apply {
        int(version)
        byte(purpose.code)
        bytes(deviceId)
        string(name)
        bytes(publicKey)
        bytes(nonce)
    }.toByteArray()

    companion object {
        /** Bytes from the network: anything that doesn't read as a hello is null. */
        fun decode(bytes: ByteArray): LanHello? = runCatching {
            val reader = ByteReader(bytes)
            val hello = LanHello(
                version = reader.int(),
                purpose = Purpose.of(reader.byte()) ?: return null,
                deviceId = reader.bytes(),
                name = reader.string(),
                publicKey = reader.bytes(),
                nonce = reader.bytes(),
            )
            hello.takeIf { reader.isDone && it.deviceId.size == DeviceIdBytes && it.nonce.size == NonceBytes }
        }.getOrNull()
    }
}

/** What goes over a connection once it is encrypted. */
internal sealed interface LanFrame {
    class Auth(val mode: AuthMode, val mac: ByteArray) : LanFrame

    /** This end's user agreed to pair, after checking the code. */
    data object Accept : LanFrame

    /** This end's user turned the pairing down (or went away). */
    data object Decline : LanFrame

    /** A key for linking up again later, from the end with the smaller id, after a new pairing. */
    class Trust(val key: ByteArray) : LanFrame

    /** Unpaired: forget this device, don't link up again by yourself. */
    data object Forget : LanFrame

    /** Sent every few seconds, so a link whose Wi-Fi went away is noticed. */
    data object Heartbeat : LanFrame

    /** A card message ([com.kotlistine.transferingfilebyair.core.transfer.data.SlideMessageCodec]). */
    class Message(val bytes: ByteArray) : LanFrame

    data class FileStart(val transferId: Long, val sizeBytes: Long) : LanFrame

    class FileChunk(val transferId: Long, val bytes: ByteArray) : LanFrame

    /** The last chunk of the file went. */
    data class FileEnd(val transferId: Long) : LanFrame

    /** The receiver has all of the file, kept: the sender's transfer is done. */
    data class FileDone(val transferId: Long) : LanFrame

    /** Either end stopped the transfer. */
    data class FileCancel(val transferId: Long) : LanFrame
}

private const val AuthType = 1
private const val AcceptType = 2
private const val DeclineType = 3
private const val TrustType = 4
private const val ForgetType = 5
private const val HeartbeatType = 6
private const val MessageType = 7
private const val FileStartType = 8
private const val FileChunkType = 9
private const val FileEndType = 10
private const val FileDoneType = 11
private const val FileCancelType = 12

internal fun LanFrame.encode(): ByteArray = ByteWriter().apply {
    when (val frame = this@encode) {
        is LanFrame.Auth -> {
            byte(AuthType)
            byte(frame.mode.code)
            bytes(frame.mac)
        }
        LanFrame.Accept -> byte(AcceptType)
        LanFrame.Decline -> byte(DeclineType)
        is LanFrame.Trust -> {
            byte(TrustType)
            bytes(frame.key)
        }
        LanFrame.Forget -> byte(ForgetType)
        LanFrame.Heartbeat -> byte(HeartbeatType)
        is LanFrame.Message -> {
            byte(MessageType)
            bytes(frame.bytes)
        }
        is LanFrame.FileStart -> {
            byte(FileStartType)
            long(frame.transferId)
            long(frame.sizeBytes)
        }
        is LanFrame.FileChunk -> {
            byte(FileChunkType)
            long(frame.transferId)
            bytes(frame.bytes)
        }
        is LanFrame.FileEnd -> {
            byte(FileEndType)
            long(frame.transferId)
        }
        is LanFrame.FileDone -> {
            byte(FileDoneType)
            long(frame.transferId)
        }
        is LanFrame.FileCancel -> {
            byte(FileCancelType)
            long(frame.transferId)
        }
    }
}.toByteArray()

/** Bytes from the other device: anything that doesn't read as exactly one frame is null. */
internal fun decodeLanFrame(bytes: ByteArray): LanFrame? = runCatching {
    val reader = ByteReader(bytes)
    val frame = when (reader.byte()) {
        AuthType -> LanFrame.Auth(AuthMode.of(reader.byte()) ?: return null, reader.bytes())
        AcceptType -> LanFrame.Accept
        DeclineType -> LanFrame.Decline
        TrustType -> LanFrame.Trust(reader.bytes())
        ForgetType -> LanFrame.Forget
        HeartbeatType -> LanFrame.Heartbeat
        MessageType -> LanFrame.Message(reader.bytes())
        FileStartType -> LanFrame.FileStart(reader.long(), reader.long()).takeIf { it.sizeBytes >= 0 }
        FileChunkType -> LanFrame.FileChunk(reader.long(), reader.bytes())
        FileEndType -> LanFrame.FileEnd(reader.long())
        FileDoneType -> LanFrame.FileDone(reader.long())
        FileCancelType -> LanFrame.FileCancel(reader.long())
        else -> null
    }
    frame?.takeIf { reader.isDone }
}.getOrNull()

/** Big-endian fields; byte arrays and strings are length-prefixed. */
internal class ByteWriter {
    private val out = ArrayList<Byte>()

    fun byte(value: Int) {
        out += value.toByte()
    }

    fun int(value: Int) {
        for (shift in 24 downTo 0 step 8) out += (value ushr shift).toByte()
    }

    fun long(value: Long) {
        for (shift in 56 downTo 0 step 8) out += (value ushr shift).toByte()
    }

    fun bytes(value: ByteArray) {
        int(value.size)
        value.forEach { out += it }
    }

    fun string(value: String) = bytes(value.encodeToByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class ByteReader(private val source: ByteArray) {
    private var position = 0

    val isDone: Boolean get() = position == source.size

    fun byte(): Int {
        require(position < source.size)
        return source[position++].toInt() and 0xFF
    }

    fun int(): Int {
        var value = 0
        repeat(4) { value = (value shl 8) or byte() }
        return value
    }

    fun long(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or byte().toLong() }
        return value
    }

    fun bytes(): ByteArray {
        val size = int()
        require(size >= 0 && size <= source.size - position)
        return source.copyOfRange(position, position + size).also { position += size }
    }

    fun string(): String = bytes().decodeToString()
}
