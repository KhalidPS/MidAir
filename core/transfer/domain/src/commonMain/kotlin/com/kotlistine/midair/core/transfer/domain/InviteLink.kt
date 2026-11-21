package com.kotlistine.midair.core.transfer.domain

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult

/**
 * A link that can pair from another device's invitation, shown as a QR code (a laptop's): seeing that screen is the
 * check, so neither user compares a code. Platforms without one (or without a camera to scan with) bind none.
 */
interface InviteLink {
    /**
     * Pairs with the device whose code was scanned ([text]). Fails with [DataError.Network.MALFORMED_MESSAGE] for a
     * code that isn't an invitation; the outcome of a real one shows in [PeerLink.state].
     */
    suspend fun joinInvite(text: String): EmptyResult<DataError.Network>
}
