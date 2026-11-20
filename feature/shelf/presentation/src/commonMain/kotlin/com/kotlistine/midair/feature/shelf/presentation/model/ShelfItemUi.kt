package com.kotlistine.midair.feature.shelf.presentation.model

import androidx.compose.runtime.Immutable
import com.kotlistine.midair.core.domain.shelf.FileKind
import com.kotlistine.midair.core.domain.shelf.ShelfItem
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Immutable
data class ShelfItemUi(
    val id: String,
    val name: String,
    /** "367 KB · 13:25" */
    val details: String,
    val kind: FileKind,
    /** Type badge for document cards ("PDF", "MD"). */
    val badge: String?,
    /** Width / height of the card's preview area. */
    val aspectRatio: Float,
    val uri: String,
    val mimeType: String,
    /** Opening lines of a text file, drawn on its page preview. */
    val textPreview: String? = null,
    val sizeBytes: Long = 0,
)

internal fun ShelfItem.toUi(now: Instant, timeZone: TimeZone): ShelfItemUi = ShelfItemUi(
    id = id,
    name = name,
    details = "${formatFileSize(sizeBytes)} · ${formatAddedTime(addedAtEpochMillis, now, timeZone)}",
    kind = kind,
    badge = extension,
    aspectRatio = cardAspectRatio(),
    uri = uri,
    mimeType = mimeType,
    textPreview = textPreview,
    sizeBytes = sizeBytes,
)

/** Decimal units, as the reference design shows them: "208 bytes", "16 KB", "1.6 MB". */
internal fun formatFileSize(bytes: Long): String = when {
    bytes == 1L -> "1 byte"
    bytes < 1_000 -> "$bytes bytes"
    // Thresholds sit at the rounding edge so 999 999 bytes reads "1 MB", not "1000 KB".
    bytes < 999_500 -> "${roundedDivision(bytes, 1_000)} KB"
    bytes < 999_950_000 -> "${oneDecimal(bytes, 1_000_000)} MB"
    else -> "${oneDecimal(bytes, 1_000_000_000)} GB"
}

/** "13:25" for today, "21 Oct" earlier this year, "21 Oct 2025" before that. */
internal fun formatAddedTime(epochMillis: Long, now: Instant, timeZone: TimeZone): String {
    val added = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    val today = now.toLocalDateTime(timeZone)
    return when {
        added.date == today.date -> "${added.hour.twoDigits()}:${added.minute.twoDigits()}"
        added.year == today.year -> "${added.day} ${monthNames[added.month.ordinal]}"
        else -> "${added.day} ${monthNames[added.month.ordinal]} ${added.year}"
    }
}

internal fun ShelfItem.cardAspectRatio(): Float = when (kind) {
    FileKind.IMAGE -> {
        val w = widthPx
        val h = heightPx
        if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.75f, 1.6f) else 4f / 3f
    }
    FileKind.VIDEO -> 16f / 10f
    FileKind.PDF, FileKind.TEXT -> 0.78f
    FileKind.OTHER -> 1f
}

private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private fun roundedDivision(value: Long, unit: Long): Long = (value + unit / 2) / unit

private fun oneDecimal(value: Long, unit: Long): String {
    val tenths = roundedDivision(value * 10, unit)
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) "$whole" else "$whole.$fraction"
}
