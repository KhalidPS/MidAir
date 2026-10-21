package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.DataError

internal fun DataError.Local.toMessage(): String = when (this) {
    DataError.Local.FILE_NOT_FOUND -> "The file couldn't be found."
    DataError.Local.PERMISSION_DENIED -> "Access to the file was denied."
    DataError.Local.DISK_FULL -> "Your device is out of storage."
    DataError.Local.UNKNOWN -> "Something went wrong."
}

internal fun ShelfEvent.ShowAddFailed.toMessage(): String {
    val what = if (failedCount == 1) "the file" else "$failedCount files"
    return "Couldn't add $what. ${error.toMessage()}"
}

internal fun ShelfEvent.ShowSent.toMessage(): String = "“$name” left the shelf"

internal const val UndoLabel = "Undo"

internal const val NoAppToOpenMessage = "No app on this device can open this file."

internal const val FileNotAccessibleMessage =
    "This file is no longer available. Remove it from the shelf and add it again."
