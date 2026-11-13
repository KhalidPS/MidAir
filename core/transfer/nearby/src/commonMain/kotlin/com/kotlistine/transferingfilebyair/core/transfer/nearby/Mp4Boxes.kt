package com.kotlistine.transferingfilebyair.core.transfer.nearby

import com.kotlistine.transferingfilebyair.core.transfer.data.ByteSpan

/** How many bytes say what a box is and how long: enough for one with a 64-bit length. */
internal const val Mp4BoxHeaderBytes = 16

/** A file of more boxes than this is not a video anyone made. */
internal const val MaxMp4Boxes = 64

/**
 * One of the boxes an MP4 file (a phone's recording, most videos) is a row of: [type] is `ftyp`, `moov` (the index:
 * where every frame is), `mdat` (the pictures and sound themselves) and a few more. It starts at [start], is [size]
 * bytes long with its header, and its content starts [headerBytes] in.
 */
internal data class Mp4Box(val type: String, val start: Long, val headerBytes: Int, val size: Long) {
    val end: Long get() = start + size

    /** What is in the box, without its header. */
    val body: ByteSpan get() = ByteSpan(start + headerBytes, end)
}

/**
 * The box that starts at [offset] of a file [fileSize] bytes long, read from the [header] bytes found there (up to
 * [Mp4BoxHeaderBytes] of them; fewer at the file's very end). Null when those bytes are no box: not an MP4 file, or
 * not a box's start.
 */
internal fun mp4BoxAt(offset: Long, header: ByteArray, fileSize: Long): Mp4Box? {
    if (header.size < 8) return null
    val type = String(CharArray(4) { (header[4 + it].toInt() and 0xFF).toChar() })
    // Box names are four printable characters.
    if (type.any { it.code !in 0x20..0x7E }) return null
    val short = header.uint(0, 4)
    val headerBytes = if (short == 1L) 16 else 8
    val size = when (short) {
        // To the file's end.
        0L -> fileSize - offset
        // Longer than 4 GB: its length follows the name.
        1L -> if (header.size >= 16) header.uint(8, 8) else return null
        else -> short
    }
    // A length past what a file can be reads as negative: no box either.
    if (size < headerBytes || size > fileSize - offset) return null
    return Mp4Box(type, offset, headerBytes, size)
}

/**
 * The parts of an MP4 file that are pictures and sound (the content of its `mdat` boxes), in file order: everything
 * else is its header and index, which show nothing by themselves. Null when the file isn't MP4 as far as can be
 * told. [headerAt] reads up to [Mp4BoxHeaderBytes] bytes at an offset, null when it can't.
 */
internal fun mp4MediaSpans(fileSize: Long, headerAt: (offset: Long) -> ByteArray?): List<ByteSpan>? {
    if (fileSize <= 0) return null
    val media = mutableListOf<ByteSpan>()
    var offset = 0L
    var boxes = 0
    while (offset < fileSize) {
        if (++boxes > MaxMp4Boxes) return null
        val box = headerAt(offset)?.let { mp4BoxAt(offset, it, fileSize) } ?: return null
        if (box.type == "mdat") media += box.body
        offset = box.end
    }
    return media
}

/**
 * What of the [length] bytes asked for at [offset] may be read of a file whose [closed] parts may not: from [offset]
 * up to the first closed part, or null when [offset] is inside one.
 */
internal fun openSpan(offset: Long, length: Long, closed: List<ByteSpan>): ByteSpan? {
    if (length <= 0) return null
    if (closed.any { offset >= it.start && offset < it.end }) return null
    val nextClosed = closed.filter { it.start > offset }.minOfOrNull { it.start } ?: Long.MAX_VALUE
    return ByteSpan(offset, minOf(offset + length, nextClosed))
}

private fun ByteArray.uint(from: Int, bytes: Int): Long {
    var value = 0L
    for (i in from until from + bytes) value = (value shl 8) or (this[i].toLong() and 0xFF)
    return value
}
