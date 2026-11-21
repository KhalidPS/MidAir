package com.kotlistine.midair.core.transfer.lan

/** What a new connection turns into, once both ends have shown who they are. */
internal enum class AuthOutcome {
    /** A device paired before, proven: linked without asking. */
    LINK,

    /** The QR code proved both ends: linked without asking, and they agree on a key for next time. */
    LINK_NEW_PAIRING,

    /** A first meeting: both screens show the code and wait for each user. */
    ASK_USERS,

    /** Nothing to do with this device now (or it failed its proof): hang up. */
    REFUSE,
}

/**
 * [mine] is how this end showed itself; [theirs] how the other end did, as far as this end could check it: null when
 * it sent a proof that didn't hold (someone posing as a paired device). A proof this end has nothing to check against
 * (it forgot the other device; it shows no QR code) counts as [AuthMode.PAIR]. Both ends decide the same way, so a
 * link comes up silently only when both proved themselves. [mayAskUsers]: this end's user is pairing right now (asked
 * for this device, or has the pairing screen open), so a first meeting may ask them.
 */
internal fun decideAuth(mine: AuthMode, theirs: AuthMode?, mayAskUsers: Boolean): AuthOutcome = when {
    theirs == null -> AuthOutcome.REFUSE
    mine == AuthMode.TRUSTED && theirs == AuthMode.TRUSTED -> AuthOutcome.LINK
    mine == AuthMode.QR && theirs == AuthMode.QR -> AuthOutcome.LINK_NEW_PAIRING
    mayAskUsers -> AuthOutcome.ASK_USERS
    else -> AuthOutcome.REFUSE
}

/** After a new pairing, the end with the smaller id hands out the key, so the two never cross. */
internal fun handsOutKey(ownId: ByteArray, peerId: ByteArray): Boolean = compareIds(ownId, peerId) < 0

/** Two paired devices both look for each other; the one with the smaller id makes the call, so they make one. */
internal fun dialsToReconnect(ownId: ByteArray, peerId: ByteArray): Boolean = compareIds(ownId, peerId) < 0

private fun compareIds(a: ByteArray, b: ByteArray): Int {
    for (index in 0 until minOf(a.size, b.size)) {
        val difference = (a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF)
        if (difference != 0) return difference
    }
    return a.size - b.size
}

/** Six digits from the first bytes of [material], zero-padded: what both users compare. */
internal fun pairingCode(material: ByteArray): String {
    require(material.size >= 4)
    var value = 0L
    for (index in 0 until 4) value = (value shl 8) or (material[index].toLong() and 0xFF)
    return (value % 1_000_000).toString().padStart(6, '0')
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
