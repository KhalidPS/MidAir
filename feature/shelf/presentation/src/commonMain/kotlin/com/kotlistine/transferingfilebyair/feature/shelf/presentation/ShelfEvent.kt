package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.DataError

/** One-off effects (navigation, snackbars, haptics) the UI consumes exactly once via ObserveAsEvents. */
sealed interface ShelfEvent {
    data class OpenFile(val uri: String, val mimeType: String) : ShelfEvent
    data class ShowAddFailed(val failedCount: Int, val error: DataError.Local) : ShelfEvent
    data class ShowError(val error: DataError.Local) : ShelfEvent
}
