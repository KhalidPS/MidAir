package com.kotlistine.transferingfilebyair.core.transfer.data

/** The bytes of a file from [start] (included) to [end] (excluded). */
data class ByteSpan(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/**
 * The parts of a file of [size] bytes that none of [have] cover, in file order, cut into spans of at most [maxBytes]
 * (what one request may ask for). [have] may overlap, and may reach past the end.
 */
fun missingSpans(size: Long, have: List<ByteSpan>, maxBytes: Long): List<ByteSpan> {
    require(maxBytes > 0)
    val missing = mutableListOf<ByteSpan>()
    var cursor = 0L
    for (span in merged(have, size)) {
        if (span.start > cursor) missing.cut(cursor, span.start, maxBytes)
        cursor = maxOf(cursor, span.end)
    }
    if (cursor < size) missing.cut(cursor, size, maxBytes)
    return missing
}

/** How many of the bytes of a file of [size] bytes [have] cover, counting overlaps once. */
fun coveredBytes(size: Long, have: List<ByteSpan>): Long = merged(have, size).sumOf { it.length }

/**
 * How to put a file of [size] bytes together from [have]: for each part of the file in order, the index in [have] to
 * copy it from, and the part. Null while any byte is missing.
 */
fun assemblyPlan(size: Long, have: List<ByteSpan>): List<Pair<Int, ByteSpan>>? {
    val plan = mutableListOf<Pair<Int, ByteSpan>>()
    var cursor = 0L
    while (cursor < size) {
        // Of the spans holding the next byte, the one reaching furthest: fewest copies.
        val index = have.indices
            .filter { have[it].start <= cursor && have[it].end > cursor }
            .maxByOrNull { have[it].end }
            ?: return null
        val end = minOf(have[index].end, size)
        plan += index to ByteSpan(cursor, end)
        cursor = end
    }
    return plan
}

/** [spans] clipped to the file, sorted, with overlapping and touching ones joined. */
private fun merged(spans: List<ByteSpan>, size: Long): List<ByteSpan> {
    val merged = mutableListOf<ByteSpan>()
    spans
        .map { ByteSpan(it.start.coerceAtLeast(0), it.end.coerceAtMost(size)) }
        .filter { it.length > 0 }
        .sortedBy { it.start }
        .forEach { span ->
            val last = merged.lastOrNull()
            if (last != null && span.start <= last.end) {
                merged[merged.lastIndex] = ByteSpan(last.start, maxOf(last.end, span.end))
            } else {
                merged += span
            }
        }
    return merged
}

private fun MutableList<ByteSpan>.cut(start: Long, end: Long, maxBytes: Long) {
    var from = start
    while (from < end) {
        val to = minOf(from + maxBytes, end)
        add(ByteSpan(from, to))
        from = to
    }
}
