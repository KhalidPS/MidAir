package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.IncomingCardUi

/** One-off effects (navigation, snackbars, haptics) the UI consumes exactly once via ObserveAsEvents. */
sealed interface ShelfEvent {
    data class OpenFile(val uri: String, val mimeType: String) : ShelfEvent

    /**
     * An item left the shelf. Offer to bring it back, then report the outcome: [ShelfAction.OnUndoSend] or
     * [ShelfAction.OnUndoOfferClosed].
     */
    data class ShowSent(val id: String, val name: String) : ShelfEvent
    data class ShowAddFailed(val failedCount: Int, val error: DataError.Local) : ShelfEvent
    data class ShowError(val error: DataError) : ShelfEvent

    /** A handed-over file didn't reach the peer; the item is back on the shelf. Offer [ShelfAction.OnRetrySend]. */
    data class ShowSendFailed(val id: String, val name: String, val error: DataError) : ShelfEvent

    // A card sliding over from the paired device. These stream at frame rate while it crosses; they are effects for
    // the incoming-card animation, not screen state, so they never recompose the shelf.

    /** The peer lifted a card: get its picture ready before it arrives. */
    data class PeerCardLifted(val card: IncomingCardUi) : ShelfEvent

    /**
     * Where the peer's card was on this screen (top-left, pixels) and how fast it moved (pixels per second) at
     * [sentAtMillis] by the peer's clock (0 when the peer doesn't say), and how close it was to arriving (0..1). The
     * screen plays these back evenly and predicts the trip ([leadMillis], one way) away.
     */
    data class PeerCardMoved(
        val id: String,
        val leftPx: Float,
        val topPx: Float,
        val velocityXPxPerS: Float,
        val velocityYPxPerS: Float,
        val tiltDegrees: Float,
        val arrival: Float,
        val entryEdge: ExitEdge,
        val sentAtMillis: Long,
        val leadMillis: Float,
    ) : ShelfEvent

    /** The peer's card went back (or couldn't be received): withdraw it. */
    data class PeerCardWithdrawn(val id: String) : ShelfEvent

    /** The peer let go past the edge: the card is ours, and flies into its slot in [ShelfState.arriving]. */
    data class PeerCardArrived(
        val id: String,
        /** How fast it was let go, in this screen's pixels per second: its flight carries on from there. */
        val velocityXPxPerS: Float = 0f,
        val velocityYPxPerS: Float = 0f,
    ) : ShelfEvent

    // Air gestures.

    /** Ask for the camera permission; answer with [ShelfAction.OnCameraPermission]. */
    data object RequestCameraPermission : ShelfEvent

    /**
     * A fist closed over the shelf at ([x], [y]) (0..1 across and down the screen): grab the card nearest to it and
     * report it with [ShelfAction.OnCardGrabbed]. Nothing to report if there is no card to grab.
     */
    data class GrabCardAt(val x: Float, val y: Float) : ShelfEvent

    /** The held card goes back into its slot (opened here, held too long, or the peer can't take it). */
    data class HeldCardPutBack(val id: String) : ShelfEvent

    /** A hand opened in front of the peer: the held card is gone from here, its file is on its way. */
    data class HeldCardCaught(val id: String) : ShelfEvent

    /**
     * The peer's held card was caught here: it bursts out of the opened hand at ([x], [y]) (0..1 across and down the
     * screen) and flies into its slot in [ShelfState.arriving].
     */
    data class PeerCardCaught(val id: String, val x: Float, val y: Float) : ShelfEvent
}
