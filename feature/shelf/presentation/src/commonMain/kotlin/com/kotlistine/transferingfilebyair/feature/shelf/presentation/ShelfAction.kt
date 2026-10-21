package com.kotlistine.transferingfilebyair.feature.shelf.presentation

/** User intents from the shelf screen, handled by [ShelfViewModel.onAction]. */
sealed interface ShelfAction {
    /** Handled by ShelfRoot, which owns the platform file picker. */
    data object OnAddClick : ShelfAction
    data class OnFilesPicked(val uris: List<String>) : ShelfAction
    data class OnOpenClick(val id: String) : ShelfAction
    data class OnRemoveClick(val id: String) : ShelfAction

    /** The card was slid off the screen edge. */
    data class OnItemSent(val id: String) : ShelfAction
    data class OnUndoSend(val id: String) : ShelfAction

    /** The offer to undo a send went away without Undo (timed out, dismissed or replaced): the send is final. */
    data class OnUndoOfferClosed(val id: String) : ShelfAction
    data object OnClearClick : ShelfAction
    data object OnClearConfirm : ShelfAction
    data object OnClearDismiss : ShelfAction
}
