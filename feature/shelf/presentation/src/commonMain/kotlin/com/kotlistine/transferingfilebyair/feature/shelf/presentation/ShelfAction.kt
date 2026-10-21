package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideFrame

/** User intents from the shelf screen, handled by [ShelfViewModel.onAction]. */
sealed interface ShelfAction {
    /** Handled by ShelfRoot, which owns the platform file picker. */
    data object OnAddClick : ShelfAction

    /** Handled by ShelfRoot: opens pairing. */
    data object OnDevicesClick : ShelfAction
    data class OnFilesPicked(val uris: List<String>) : ShelfAction
    data class OnOpenClick(val id: String) : ShelfAction
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

    /** A received card finished flying into its slot. */
    data object OnLandingFinished : ShelfAction

    /** The arriving card was tapped while its file was still coming in: stop the transfer. */
    data class OnIncomingCardTap(val id: String) : ShelfAction

    /** Send again a file whose transfer failed (from the failure message). */
    data class OnRetrySend(val id: String) : ShelfAction
}
