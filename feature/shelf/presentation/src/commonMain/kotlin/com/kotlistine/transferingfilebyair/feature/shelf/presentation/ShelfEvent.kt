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

    /** Where the peer's card is now on this screen (top-left, pixels), and how close it is to arriving (0..1). */
    data class PeerCardMoved(
        val id: String,
        val leftPx: Float,
        val topPx: Float,
        val tiltDegrees: Float,
        val arrival: Float,
        val entryEdge: ExitEdge,
    ) : ShelfEvent

    /** The peer's card went back (or couldn't be received): withdraw it. */
    data class PeerCardWithdrawn(val id: String) : ShelfEvent

    /** How much of the arriving card's file is here (0..1), while it comes in. */
    data class PeerCardProgress(val id: String, val fraction: Float) : ShelfEvent

    /** The peer let go past the edge: the card is ours. It lands once [ShelfState.landingItemId] appears. */
    data class PeerCardArrived(val id: String) : ShelfEvent
}
