package com.kotlistine.transferingfilebyair.core.transfer.nearby

/** First byte of a link frame. Never the first byte of a slide message (in CBOR it only ends a list). */
private const val FrameMarker: Byte = 0xFF.toByte()

internal const val DeviceIdBytes = 16
internal const val NonceBytes = 16
internal const val KeyBytes = 32
internal const val ProofBytes = 32

/** A SHA-256 digest. */
internal const val DigestBytes = 32

/** A shared file's id is chosen by the sharing app; anything longer is not one. */
internal const val MaxFileIdBytes = 256

/** The most one range request may ask for; the asking phone asks for far less at a time. */
internal const val MaxRangeBytes = 64L * 1024 * 1024

/**
 * A phone this one paired with, after both users checked the code: it may link up again without asking, once it
 * proves it holds [key] (agreed over that checked link).
 */
internal class TrustedPeer(val id: ByteArray, val name: String, val key: ByteArray)

/**
 * What the two link ends tell each other about themselves, apart from slide messages: who they are, proof that they
 * paired before, the key for next time, and that they are no longer paired.
 */
internal sealed interface LinkFrame {
    /** Sent by both ends as soon as they connect. [nonce] is what the other end must answer with a [Proof]. */
    class Hello(val deviceId: ByteArray, val nonce: ByteArray) : LinkFrame

    /** The answer to a [Hello] on a link that came up by itself: see [proofInput]. */
    class Proof(val mac: ByteArray) : LinkFrame

    /** After a pairing both users confirmed: the key for linking up again, from the end with the smaller id. */
    class Trust(val key: ByteArray) : LinkFrame

    /** The user unpaired: forget this phone, and don't link up with it again by yourself. */
    data object Forget : LinkFrame

    /**
     * Asks for [length] bytes of the shared file [fileId] from byte [offset] (fewer at its end); answered by
     * [RangeOpened] or [RangeRefused]. Ranges have a length so they always end by themselves: a stream payload
     * cancelled while it is still sending can stall the whole Nearby connection.
     */
    data class RangeRequest(val requestId: Long, val offset: Long, val length: Long, val fileId: String) : LinkFrame

    /** The range asked for as [requestId] is on its way as the stream payload [payloadId]. */
    data class RangeOpened(val requestId: Long, val payloadId: Long) : LinkFrame

    /** The range (or digest) can't be sent (the file isn't shared, or can't be read). */
    data class RangeRefused(val requestId: Long) : LinkFrame

    /**
     * Asks for the SHA-256 of the whole shared file [fileId], to check a copy put together from its ranges; answered
     * by [Digest] or [RangeRefused].
     */
    data class DigestRequest(val requestId: Long, val fileId: String) : LinkFrame

    /** The SHA-256 asked for as [requestId]. */
    class Digest(val requestId: Long, val sha256: ByteArray) : LinkFrame {
        override fun equals(other: Any?): Boolean =
            other is Digest && requestId == other.requestId && sha256.contentEquals(other.sha256)

        override fun hashCode(): Int = 31 * requestId.hashCode() + sha256.contentHashCode()
    }
}

internal fun LinkFrame.encode(): ByteArray = when (this) {
    is LinkFrame.Hello -> byteArrayOf(FrameMarker, 1) + deviceId + nonce
    is LinkFrame.Proof -> byteArrayOf(FrameMarker, 2) + mac
    is LinkFrame.Trust -> byteArrayOf(FrameMarker, 3) + key
    LinkFrame.Forget -> byteArrayOf(FrameMarker, 4)
    is LinkFrame.RangeRequest -> byteArrayOf(FrameMarker, 5) + requestId.toBytes() + offset.toBytes() +
        length.toBytes() + fileId.encodeToByteArray()
    is LinkFrame.RangeOpened -> byteArrayOf(FrameMarker, 6) + requestId.toBytes() + payloadId.toBytes()
    is LinkFrame.RangeRefused -> byteArrayOf(FrameMarker, 7) + requestId.toBytes()
    is LinkFrame.DigestRequest -> byteArrayOf(FrameMarker, 8) + requestId.toBytes() + fileId.encodeToByteArray()
    is LinkFrame.Digest -> byteArrayOf(FrameMarker, 9) + requestId.toBytes() + sha256
}

/** Whether [bytes] are a link frame (and not a slide message). */
internal fun isLinkFrame(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == FrameMarker

/** Null for anything malformed: a wrong length is a broken or hostile frame, never a shorter one. */
internal fun decodeLinkFrame(bytes: ByteArray): LinkFrame? {
    if (!isLinkFrame(bytes)) return null
    val body = bytes.copyOfRange(2, bytes.size)
    return when (bytes[1].toInt()) {
        1 -> if (body.size == DeviceIdBytes + NonceBytes) {
            LinkFrame.Hello(body.copyOfRange(0, DeviceIdBytes), body.copyOfRange(DeviceIdBytes, body.size))
        } else {
            null
        }
        2 -> if (body.size == ProofBytes) LinkFrame.Proof(body) else null
        3 -> if (body.size == KeyBytes) LinkFrame.Trust(body) else null
        4 -> if (body.isEmpty()) LinkFrame.Forget else null
        5 -> {
            val fileIdBytes = body.size - 3 * Long.SIZE_BYTES
            if (fileIdBytes !in 1..MaxFileIdBytes) return null
            val offset = body.longAt(Long.SIZE_BYTES)
            val length = body.longAt(2 * Long.SIZE_BYTES)
            if (offset >= 0 && length in 1..MaxRangeBytes) {
                val fileId = body.copyOfRange(3 * Long.SIZE_BYTES, body.size).decodeToString()
                LinkFrame.RangeRequest(body.longAt(0), offset, length, fileId)
            } else {
                null
            }
        }
        6 -> if (body.size == 2 * Long.SIZE_BYTES) {
            LinkFrame.RangeOpened(body.longAt(0), body.longAt(Long.SIZE_BYTES))
        } else {
            null
        }
        7 -> if (body.size == Long.SIZE_BYTES) LinkFrame.RangeRefused(body.longAt(0)) else null
        8 -> if (body.size - Long.SIZE_BYTES in 1..MaxFileIdBytes) {
            LinkFrame.DigestRequest(body.longAt(0), body.copyOfRange(Long.SIZE_BYTES, body.size).decodeToString())
        } else {
            null
        }
        9 -> if (body.size == Long.SIZE_BYTES + DigestBytes) {
            LinkFrame.Digest(body.longAt(0), body.copyOfRange(Long.SIZE_BYTES, body.size))
        } else {
            null
        }
        else -> null
    }
}

/** Big-endian. */
private fun Long.toBytes(): ByteArray =
    ByteArray(Long.SIZE_BYTES) { i -> (this shr (8 * (Long.SIZE_BYTES - 1 - i))).toByte() }

private fun ByteArray.longAt(index: Int): Long {
    var value = 0L
    for (i in 0 until Long.SIZE_BYTES) value = (value shl 8) or (this[index + i].toLong() and 0xFF)
    return value
}

/**
 * What a proof signs: the nonce the asking end sent, and the id of the end answering. Signing its own id with the
 * other's fresh nonce means a proof can't be replayed on another link, or bounced back to the end that asked.
 */
internal fun proofInput(askerNonce: ByteArray, answererId: ByteArray): ByteArray = askerNonce + answererId

/**
 * Two trusted phones that find each other both want to link up; only one asks, so the requests never cross: the one
 * with the smaller id.
 */
internal fun asksToReconnect(ownId: ByteArray, peerId: ByteArray): Boolean = compareIds(ownId, peerId) < 0

/** The end that sends [LinkFrame.Trust] after a confirmed pairing: also the one with the smaller id. */
internal fun handsOutKey(ownId: ByteArray, peerId: ByteArray): Boolean = compareIds(ownId, peerId) < 0

private fun compareIds(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (diff != 0) return diff
    }
    return a.size - b.size
}
