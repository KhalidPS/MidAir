package com.kotlistine.midair.core.domain.shelf

/** How a file is presented: a photo thumbnail, a video tile, or a document page. */
enum class FileKind {
    IMAGE,
    VIDEO,
    PDF,
    TEXT,
    OTHER;

    companion object {
        private val textExtensions = setOf("txt", "md", "markdown", "csv", "json", "xml", "log")

        /** The mime type wins; the file extension is the fallback for providers that report a generic type. */
        fun of(mimeType: String, fileName: String): FileKind {
            val mime = mimeType.lowercase()
            val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return when {
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime == "application/pdf" -> PDF
                mime.startsWith("text/") -> TEXT
                ext == "pdf" -> PDF
                ext in textExtensions -> TEXT
                else -> OTHER
            }
        }
    }
}
