package com.kotlistine.transferingfilebyair.feature.shelf.data.file

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.kotlistine.transferingfilebyair.core.domain.shelf.FileKind
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class AndroidFileMetadataSource(context: Context) : FileMetadataSource {

    private val context: Context = context.applicationContext
    private val resolver: ContentResolver = this.context.contentResolver

    override suspend fun read(uri: String): Result<FileMetadata, DataError.Local> = withContext(Dispatchers.IO) {
        guarded {
            val parsed = Uri.parse(uri)
            val (displayName, reportedSize) = queryNameAndSize(parsed) ?: throw FileNotFoundException(uri)
            val mimeType = resolver.getType(parsed) ?: DEFAULT_MIME_TYPE
            val name = displayName ?: parsed.lastPathSegment ?: FALLBACK_NAME
            val kind = FileKind.of(mimeType = mimeType, fileName = name)
            val bounds = if (kind == FileKind.IMAGE) readImageBounds(parsed) else null
            FileMetadata(
                name = name,
                mimeType = mimeType,
                sizeBytes = reportedSize ?: measureSize(parsed) ?: 0L,
                widthPx = bounds?.first,
                heightPx = bounds?.second,
                textPreview = if (kind == FileKind.TEXT) readTextPreview(parsed) else null,
            )
        }
    }

    override suspend fun retainAccess(uri: String): EmptyResult<DataError.Local> = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        guarded {
            if (DocumentsContract.isDocumentUri(context, parsed)) {
                resolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                // A file this app stored itself (one it received): readable without any grant, as long as it exists.
                resolver.openInputStream(parsed)?.close() ?: throw FileNotFoundException(uri)
            }
        }
    }

    override suspend fun releaseAccess(uri: String) {
        withContext(Dispatchers.IO) {
            try {
                resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Access was never persisted or is already gone; nothing to give back.
            }
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long?>? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(0)
                val size = if (cursor.isNull(1)) null else cursor.getLong(1)
                name to size
            }

    private fun measureSize(uri: Uri): Long? =
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }

    /** Display-oriented (EXIF-rotated) width and height, or null when the image can't be decoded. */
    private fun readImageBounds(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: IOException) {
            null
        }
        val rotated = orientation in setOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        )
        return if (rotated) options.outHeight to options.outWidth else options.outWidth to options.outHeight
    }

    /** The opening of a text file, decoded as UTF-8 (invalid bytes become replacement characters). */
    private fun readTextPreview(uri: Uri): String? =
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(TEXT_PREVIEW_BYTES)
            var read = 0
            while (read < buffer.size) {
                val count = stream.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            // A multi-byte character cut by the read limit decodes to U+FFFD, and take() can split a surrogate
            // pair (emoji); drop either along with trailing whitespace.
            buffer.decodeToString(0, read).take(TEXT_PREVIEW_CHARS)
                .trimEnd { it.isWhitespace() || it == '�' || it.isHighSurrogate() }
                .ifEmpty { null }
        }

    private inline fun <T> guarded(block: () -> T): Result<T, DataError.Local> =
        try {
            Result.Success(block())
        } catch (_: FileNotFoundException) {
            Result.Failure(DataError.Local.FILE_NOT_FOUND)
        } catch (_: SecurityException) {
            Result.Failure(DataError.Local.PERMISSION_DENIED)
        } catch (_: IOException) {
            Result.Failure(DataError.Local.UNKNOWN)
        } catch (_: IllegalArgumentException) {
            // Thrown by the content resolver for unknown or malformed URIs.
            Result.Failure(DataError.Local.FILE_NOT_FOUND)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // Third-party document providers can fail with anything across Binder (IllegalStateException,
            // UnsupportedOperationException, NullPointerException...); none of it may escape the data layer.
            Result.Failure(DataError.Local.UNKNOWN)
        }

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val FALLBACK_NAME = "file"
        const val TEXT_PREVIEW_BYTES = 2_048
        const val TEXT_PREVIEW_CHARS = 600
    }
}
