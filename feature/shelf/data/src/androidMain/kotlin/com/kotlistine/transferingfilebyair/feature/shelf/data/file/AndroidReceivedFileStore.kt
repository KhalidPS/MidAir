package com.kotlistine.transferingfilebyair.feature.shelf.data.file

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

private const val FolderName = "TransferingFileByAir"

/** Big reads and writes: a received video is hundreds of megabytes, and 8 KB steps made its copy crawl. */
private const val CopyBufferBytes = 256 * 1024

/**
 * Keeps received files in Downloads/TransferingFileByAir (Android 10+, through MediaStore, no storage permission),
 * or in the app's own Downloads folder shared through a FileProvider on older versions. Either way the reference
 * stays readable by the app and can be handed to viewers.
 */
internal class AndroidReceivedFileStore(context: Context) : ReceivedFileStore {

    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override suspend fun keep(location: String, name: String, mimeType: String): Result<String, DataError.Local> =
        withContext(Dispatchers.IO) {
            try {
                val source = Uri.parse(location)
                val kept = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    keepInDownloads(source, name, mimeType)
                } else {
                    keepInAppFolder(source, name)
                }
                // The transport's own copy is no longer needed (best effort: it may not be ours to delete).
                runCatching { resolver.delete(source, null, null) }
                Result.Success(kept.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (_: FileNotFoundException) {
                Result.Failure(DataError.Local.FILE_NOT_FOUND)
            } catch (_: SecurityException) {
                Result.Failure(DataError.Local.PERMISSION_DENIED)
            } catch (e: IOException) {
                val full = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true
                Result.Failure(if (full) DataError.Local.DISK_FULL else DataError.Local.UNKNOWN)
            }
        }

    private fun keepInDownloads(source: Uri, name: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FolderName")
            // Hidden from other apps until the copy is complete.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create $name in Downloads")
        try {
            copy(source, target)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
        resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return target
    }

    private fun keepInAppFolder(source: Uri, name: String): Uri {
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FolderName).apply { mkdirs() }
        val file = uniqueFile(folder, name)
        resolver.openInputStream(source)?.use { input -> file.outputStream().use { input.copyTo(it, CopyBufferBytes) } }
            ?: throw FileNotFoundException(source.toString())
        return FileProvider.getUriForFile(context, "${context.packageName}.received", file)
    }

    private fun copy(source: Uri, target: Uri) {
        val input = resolver.openInputStream(source) ?: throw FileNotFoundException(source.toString())
        input.use {
            val output = resolver.openOutputStream(target) ?: throw IOException("Couldn't write $target")
            output.use { input.copyTo(it, CopyBufferBytes) }
        }
    }

    /** "photo.jpg", then "photo (1).jpg", … so a second file with the same name never replaces the first. */
    private fun uniqueFile(folder: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val extension = name.substring(dot)
        var candidate = File(folder, name)
        var n = 1
        while (candidate.exists()) candidate = File(folder, "$base (${n++})$extension")
        return candidate
    }
}
