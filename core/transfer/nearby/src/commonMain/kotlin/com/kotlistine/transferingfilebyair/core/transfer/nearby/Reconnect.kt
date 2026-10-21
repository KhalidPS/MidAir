package com.kotlistine.transferingfilebyair.core.transfer.nearby

/** First byte of a link frame. Never the first byte of a slide message (in CBOR it only ends a list). */
private const val FrameMarker: Byte = 0xFF.toByte()

internal const val DeviceIdBytes = 16
internal const val NonceBytes = 16
internal const val KeyBytes = 32
internal const val ProofBytes = 32

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
}

internal fun LinkFrame.encode(): ByteArray = when (this) {
    is LinkFrame.Hello -> byteArrayOf(FrameMarker, 1) + deviceId + nonce
    is LinkFrame.Proof -> byteArrayOf(FrameMarker, 2) + mac
    is LinkFrame.Trust -> byteArrayOf(FrameMarker, 3) + key
    LinkFrame.Forget -> byteArrayOf(FrameMarker, 4)
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
        else -> null
    }
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
