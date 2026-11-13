package com.kotlistine.transferingfilebyair.feature.shelf.data.file

import java.io.File
import java.net.URI

private const val AppFolderName = "TransferingFileByAir"

/** The app's own data folder: `%APPDATA%` on Windows, `~/Library/Application Support` on macOS, `~/.local/share`. */
internal fun desktopAppDataFolder(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        os.contains("win") -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
        os.contains("mac") -> File(home, "Library/Application Support")
        else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
    }
    return File(base, AppFolderName).apply { mkdirs() }
}

/** Where received files go: `~/Downloads/TransferingFileByAir`, as on the phone. */
internal fun desktopReceivedFolder(): File =
    File(System.getProperty("user.home"), "Downloads/$AppFolderName").apply { mkdirs() }

/** A `file:///` URI or a plain path, as a file. */
internal fun fileOf(location: String): File =
    if (location.startsWith("file:")) File(URI(location)) else File(location)
