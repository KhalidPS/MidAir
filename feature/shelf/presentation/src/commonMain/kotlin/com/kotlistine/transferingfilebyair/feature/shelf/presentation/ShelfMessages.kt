package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.DataError

internal fun DataError.toMessage(): String = when (this) {
    DataError.Local.FILE_NOT_FOUND -> "The file couldn't be found."
    DataError.Local.PERMISSION_DENIED -> "Access to the file was denied."
    DataError.Local.DISK_FULL -> "Your device is out of storage."
    DataError.Local.UNSUPPORTED_FORMAT -> "This device can't play this file."
    DataError.Local.UNKNOWN -> "Something went wrong."
    DataError.Network.DISCONNECTED -> "The other device isn't connected."
    DataError.Network.PERMISSION_MISSING -> "Allow nearby devices to pair with the device next to you."
    DataError.Network.RADIO_OFF -> "Turn on Bluetooth and Wi-Fi to reach the device next to you."
    DataError.Network.LOCATION_OFF -> "Turn on Location to reach the device next to you."
    DataError.Network.CANCELLED -> "The transfer was cancelled."
    DataError.Network.PAYLOAD_TOO_LARGE,
    DataError.Network.MALFORMED_MESSAGE,
    DataError.Network.UNKNOWN,
    -> "Couldn't talk to the other device."
}

internal fun ShelfEvent.ShowAddFailed.toMessage(): String {
    val what = if (failedCount == 1) "the file" else "$failedCount files"
    return "Couldn't add $what. ${error.toMessage()}"
}

internal fun ShelfEvent.ShowSent.toMessage(): String = "“$name” left the shelf"

internal const val UndoLabel = "Undo"

internal const val RetryLabel = "Retry"

internal fun ShelfEvent.ShowSendFailed.toMessage(): String =
    if (error == DataError.Network.CANCELLED) {
        "“$name” wasn't sent: the other phone stopped it."
    } else {
        "Couldn't send “$name”. It's back on your shelf."
    }

internal const val NoAppToOpenMessage = "No app on this device can open this file."

internal const val FileNotAccessibleMessage =
    "This file is no longer available. Remove it from the shelf and add it again."
