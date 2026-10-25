package com.kotlistine.transferingfilebyair.core.transfer.nearby

/** What a `Range` request header asks of a file of a given size. */
internal sealed interface ByteRangeRequest {
    /** No range: the whole file. */
    data object Whole : ByteRangeRequest

    /** Bytes [first]..[last], both included and within the file. */
    data class Part(val first: Long, val last: Long) : ByteRangeRequest {
        val length: Long get() = last - first + 1
    }

    /** A range outside the file, or one this server doesn't read (several ranges at once). */
    data object Unsatisfiable : ByteRangeRequest
}

/**
 * Reads a `Range` header ([header], null when absent) for a file of [size] bytes. Handles the forms a video player
 * sends: `bytes=a-`, `bytes=a-b` (b past the end is cut to the end) and `bytes=-n` (the last n bytes).
 */
internal fun parseByteRange(header: String?, size: Long): ByteRangeRequest {
    if (header == null) return ByteRangeRequest.Whole
    val spec = header.trim()
    if (!spec.startsWith("bytes=", ignoreCase = true)) return ByteRangeRequest.Unsatisfiable
    val range = spec.substring("bytes=".length).trim()
    if (',' in range || '-' !in range) return ByteRangeRequest.Unsatisfiable
    val start = range.substringBefore('-').trim()
    val end = range.substringAfter('-').trim()
    if (size <= 0) return ByteRangeRequest.Unsatisfiable
    if (start.isEmpty()) {
        val suffix = end.toLongOrNull()?.takeIf { it > 0 } ?: return ByteRangeRequest.Unsatisfiable
        return ByteRangeRequest.Part(first = (size - suffix).coerceAtLeast(0), last = size - 1)
    }
    val first = start.toLongOrNull()?.takeIf { it in 0 until size } ?: return ByteRangeRequest.Unsatisfiable
    val last = if (end.isEmpty()) size - 1 else end.toLongOrNull() ?: return ByteRangeRequest.Unsatisfiable
    if (last < first) return ByteRangeRequest.Unsatisfiable
    return ByteRangeRequest.Part(first, last.coerceAtMost(size - 1))
}
