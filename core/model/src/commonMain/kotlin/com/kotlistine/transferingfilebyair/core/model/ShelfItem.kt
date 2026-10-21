package com.kotlistine.transferingfilebyair.core.model

/**
 * A file staged on the shelf, waiting to be slid to the paired device.
 *
 * [uri] is a platform-opaque reference (a `content://` URI on Android, a path on desktop).
 * The original file is never modified; sending only removes the item from the shelf.
 */
data class ShelfItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAtEpochMillis: Long,
    val uri: String,
)
