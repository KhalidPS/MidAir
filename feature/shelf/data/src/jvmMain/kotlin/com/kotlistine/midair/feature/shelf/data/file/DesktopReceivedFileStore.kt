package com.kotlistine.midair.feature.shelf.data.file

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Characters no desktop file system takes in a name, and the path separators a peer must never slip in. */
private val UnsafeNameChars = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
private const val FallbackName = "file"

/** Keeps received files in `~/Downloads/TransferingFileByAir`, never replacing one already there. */
internal class DesktopReceivedFileStore(
    private val folder: () -> File = ::desktopReceivedFolder,
) : ReceivedFileStore {

    override suspend fun keep(location: String, name: String, mimeType: String): Result<String, DataError.Local> =
        withContext(Dispatchers.IO) {
            try {
                val target = uniqueFile(folder(), safeName(name))
                Files.copy(fileOf(location).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Result.Success(target.toPath().toUri().toString())
            } catch (_: FileNotFoundException) {
                Result.Failure(DataError.Local.FILE_NOT_FOUND)
            } catch (_: java.nio.file.NoSuchFileException) {
                Result.Failure(DataError.Local.FILE_NOT_FOUND)
            } catch (_: SecurityException) {
                Result.Failure(DataError.Local.PERMISSION_DENIED)
            } catch (e: IOException) {
                val full = folder().usableSpace < fileOf(location).length()
                Result.Failure(if (full) DataError.Local.DISK_FULL else DataError.Local.UNKNOWN)
            }
        }
}

/** The name the peer gave, made safe as a single file name in our folder. */
internal fun safeName(name: String): String {
    val cleaned = name.replace(UnsafeNameChars, "_").trim().trimStart('.')
    return cleaned.ifEmpty { FallbackName }
}

/** "photo.jpg", then "photo (1).jpg", … so a second file with the same name never replaces the first. */
internal fun uniqueFile(folder: File, name: String): File {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val extension = name.substring(dot)
    var candidate = File(folder, name)
    var index = 1
    while (candidate.exists()) {
        candidate = File(folder, "$base ($index)$extension")
        index++
    }
    return candidate
}
