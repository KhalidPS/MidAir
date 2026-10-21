package com.kotlistine.transferingfilebyair.core.domain.shelf

/**
 * A file staged on the shelf, waiting to be slid to the paired device.
 *
 * [uri] is a platform-opaque reference (a `content://` URI on Android, a path on desktop).
 * The original file is never modified; sending only removes the item from the shelf.
 * [widthPx]/[heightPx] are known for images only and let the grid size a card before its thumbnail loads.
 * [textPreview] holds the opening of a text file so its card can show real content.
 */
data class ShelfItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAtEpochMillis: Long,
    val uri: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val textPreview: String? = null,
) {
    val kind: FileKind get() = FileKind.of(mimeType = mimeType, fileName = name)

    /** Upper-case extension used as a type badge ("PDF", "MD"), or null when the name has none. */
    val extension: String?
        get() {
            val dot = name.lastIndexOf('.')
            // A leading dot is a hidden file (".gitignore"), a trailing dot has no extension.
            if (dot <= 0 || dot == name.lastIndex) return null
            return name.substring(dot + 1).takeIf { it.length <= 5 }?.uppercase()
        }
}
