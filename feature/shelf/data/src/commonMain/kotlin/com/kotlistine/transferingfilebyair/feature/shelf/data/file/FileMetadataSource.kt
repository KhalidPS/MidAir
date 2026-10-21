package com.kotlistine.transferingfilebyair.feature.shelf.data.file

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result

data class FileMetadata(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    /** The first few hundred characters of a text file, shown on its card. */
    val textPreview: String? = null,
)

/** Platform access to files the user picked (content resolver on Android, file system on desktop). */
interface FileMetadataSource {

    suspend fun read(uri: String): Result<FileMetadata, DataError.Local>

    /** Keeps read access across app restarts, so a staged file is still sendable tomorrow. */
    suspend fun retainAccess(uri: String): EmptyResult<DataError.Local>

    /** Gives back access taken by [retainAccess]. Best effort: never fails the caller. */
    suspend fun releaseAccess(uri: String)
}
