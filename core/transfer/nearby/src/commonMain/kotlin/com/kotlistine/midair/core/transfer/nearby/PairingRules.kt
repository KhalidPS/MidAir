package com.kotlistine.midair.core.transfer.nearby

import com.kotlistine.midair.core.transfer.domain.LinkState

/** What to do with a connection request as it begins. */
internal enum class RequestDecision {
    /** Show who it is and the code, and connect only once this phone's user confirms. */
    ASK_USER,

    /** Not now: already paired, mid-way through an attempt with someone else, or nothing this phone asked for. */
    REJECT,
}

/**
 * The consent rule for pairing, the same on both phones: every attempt waits for its user to confirm the code both
 * phones show (also on the phone that asked, so a device copying a name gets nowhere), and while a phone is paired
 * or pairing with one device, requests from any other are turned down. A request from the very device this phone
 * is asking is the same attempt seen from the other side (both people held Connect at once), so it is not refused.
 */
internal fun decideRequest(
    isIncoming: Boolean,
    endpointId: String,
    requestedEndpoint: String?,
    connectedEndpoint: String?,
    state: LinkState,
): RequestDecision = when {
    connectedEndpoint != null -> RequestDecision.REJECT
    endpointId == requestedEndpoint -> RequestDecision.ASK_USER
    // An outgoing request this phone never made.
    !isIncoming -> RequestDecision.REJECT
    requestedEndpoint != null -> RequestDecision.REJECT
    // Someone else is waiting for this user, or this user already agreed to someone.
    state is LinkState.Incoming || state is LinkState.Connecting -> RequestDecision.REJECT
    else -> RequestDecision.ASK_USER
}

/** Whether a connection outcome for [endpointId] belongs to the attempt this phone is in (not one it turned down). */
internal fun isCurrentAttempt(endpointId: String, requestedEndpoint: String?, state: LinkState): Boolean =
    endpointId == requestedEndpoint ||
        (state is LinkState.Incoming && state.peer.id == endpointId) ||
        (state is LinkState.Connecting && state.peer.id == endpointId)

/** The endpoint of a pairing attempt still waiting on someone, which must be withdrawn when the user leaves. */
internal fun pendingEndpoint(requestedEndpoint: String?, state: LinkState): String? = when (state) {
    is LinkState.Incoming -> state.peer.id
    is LinkState.Connecting -> state.peer.id
    else -> requestedEndpoint
}
