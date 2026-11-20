package com.kotlistine.midair.feature.shelf.data.file

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult
import com.kotlistine.midair.core.domain.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import javax.imageio.ImageIO

private const val DefaultMimeType = "application/octet-stream"
private const val TextPreviewBytes = 2_048
private const val TextPreviewChars = 600

/** Files on the desktop's own disk, picked as `file:///` URIs. Access needs no keeping: a path stays readable. */
internal class DesktopFileMetadataSource : FileMetadataSource {

    override suspend fun read(uri: String): Result<FileMetadata, DataError.Local> = withContext(Dispatchers.IO) {
        try {
            val file = fileOf(uri)
            if (!file.isFile) return@withContext Result.Failure(DataError.Local.FILE_NOT_FOUND)
            val mimeType = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: DefaultMimeType
            val bounds = if (mimeType.startsWith("image/")) imageBounds(file) else null
            Result.Success(
                FileMetadata(
                    name = file.name,
                    mimeType = mimeType,
                    sizeBytes = file.length(),
                    widthPx = bounds?.first,
                    heightPx = bounds?.second,
                    textPreview = if (mimeType.startsWith("text/")) textPreview(file) else null,
                ),
            )
        } catch (_: FileNotFoundException) {
            Result.Failure(DataError.Local.FILE_NOT_FOUND)
        } catch (_: SecurityException) {
            Result.Failure(DataError.Local.PERMISSION_DENIED)
        } catch (_: Exception) {
            Result.Failure(DataError.Local.UNKNOWN)
        }
    }

    override suspend fun retainAccess(uri: String): EmptyResult<DataError.Local> =
        if (fileOf(uri).canRead()) Result.Success(Unit) else Result.Failure(DataError.Local.PERMISSION_DENIED)

    override suspend fun releaseAccess(uri: String) = Unit

    /** Reads only the image's header, not its pixels. */
    private fun imageBounds(file: File): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(file).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    private fun textPreview(file: File): String? = runCatching {
        val bytes = file.inputStream().use { it.readNBytes(TextPreviewBytes) }
        bytes.decodeToString().take(TextPreviewChars)
    }.getOrNull()
}
