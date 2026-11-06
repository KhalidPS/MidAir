package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKey
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideFrame

/** User intents from the shelf screen, handled by [ShelfViewModel.onAction]. */
sealed interface ShelfAction {
    /** Handled by ShelfRoot, which owns the platform file picker. */
    data object OnAddClick : ShelfAction

    /** Handled by ShelfRoot: opens pairing. */
    data object OnDevicesClick : ShelfAction
    data class OnFilesPicked(val uris: List<String>) : ShelfAction

    /** Opens the file in another app (from the card's menu). */
    data class OnOpenClick(val id: String) : ShelfAction

    /** The card's picture was tapped: a video plays or pauses in its card, any other file opens. */
    data class OnPreviewClick(val id: String) : ShelfAction

    /** The shelf went out of sight (another screen on top, or the app in the background): a playing video pauses. */
    data object OnScreenHidden : ShelfAction
    data class OnRemoveClick(val id: String) : ShelfAction

    /** The card was slid off the screen through [edge] (null when it left without a slide). */
    data class OnItemSent(val id: String, val edge: ExitEdge? = null) : ShelfAction
    data class OnUndoSend(val id: String) : ShelfAction

    /** The offer to undo a send went away without Undo (timed out, dismissed or replaced): the send is final. */
    data class OnUndoOfferClosed(val id: String) : ShelfAction

    data object OnClearClick : ShelfAction
    data object OnClearConfirm : ShelfAction
    data object OnClearDismiss : ShelfAction

    // The carried card, reported so a paired device can show it continuing past the edge.

    /** A card was lifted. Not a data class: [thumbnail] is compared by identity. */
    class OnSlideLifted(
        val id: String,
        val cardWidthPx: Float,
        val cardHeightPx: Float,
        val thumbnail: ByteArray?,
    ) : ShelfAction

    data class OnSlideMoved(val frame: SlideFrame) : ShelfAction
    data class OnSlideReturned(val id: String) : ShelfAction

    // Cards arriving from the paired device.

    /** The shelf's size on screen, needed to place a card arriving from the peer. */
    data class OnViewportChanged(val widthPx: Int, val heightPx: Int) : ShelfAction

    /** An arriving card was tapped while its file was still coming in: stop that transfer. */
    data class OnIncomingCardTap(val id: String) : ShelfAction

    /** Send again a file whose transfer failed (from the failure message). */
    data class OnRetrySend(val id: String) : ShelfAction

    // Transfers.

    /** The transfers pill was tapped: show every file moving either way. */
    data object OnTransfersClick : ShelfAction

    data object OnTransfersDismiss : ShelfAction

    /** Stop one file, queued or moving, either way. */
    data class OnCancelTransfer(val key: TransferKey) : ShelfAction

    data object OnCancelAllTransfers : ShelfAction

    /** Take the finished transfers off the list. */
    data object OnClearFinishedTransfers : ShelfAction

    // Air gestures.

    /** The hand button: turn watching for a fist over the shelf on or off. */
    data object OnAirGestureToggle : ShelfAction

    /** Whether the camera may be used; [justAsked] when this is the answer to [ShelfEvent.RequestCameraPermission]. */
    data class OnCameraPermission(val granted: Boolean, val justAsked: Boolean = false) : ShelfAction

    /** The shelf came on screen or left it: the camera only watches while it is shown. */
    data class OnScreenVisibilityChanged(val isVisible: Boolean) : ShelfAction

    /**
     * The card picked for a [ShelfEvent.GrabCardAt] is now in the hand, with its picture.
     * Not a data class: [thumbnail] is compared by identity.
     */
    class OnCardGrabbed(
        val id: String,
        val cardWidthPx: Float,
        val cardHeightPx: Float,
        val thumbnail: ByteArray?,
    ) : ShelfAction

    /** The hint for a card held over from the peer was tapped: catch it without the camera. */
    data object OnCatchTap : ShelfAction

    /** The hint for the card held in this user's hand was tapped: put it back in its slot. */
    data object OnPutBackTap : ShelfAction
}
