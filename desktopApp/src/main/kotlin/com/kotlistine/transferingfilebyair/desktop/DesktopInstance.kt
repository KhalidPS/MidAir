package com.kotlistine.transferingfilebyair.desktop

import java.io.File
import java.net.InetAddress

/**
 * Which copy of the app this is. Normally the only one; `AIR_INSTANCE=2` starts a second one on the same computer,
 * with its own data and name, so two windows can pair with each other (for trying the link without a phone).
 * The shelf's data module reads the same variable for its database.
 */
internal object DesktopInstance {
    private val number: String? = System.getenv("AIR_INSTANCE")?.takeIf { it.isNotBlank() && it != "1" }

    /** The app's data folder (the shelf's database lives here too). */
    val dataFolder: File by lazy {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            os.contains("win") -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
            os.contains("mac") -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        }
        File(base, "TransferingFileByAir" + (number?.let { "-$it" } ?: "")).apply { mkdirs() }
    }

    /** How this computer shows up on the phone: its network name, like Windows shows it. */
    val deviceName: String by lazy {
        val name = System.getenv("COMPUTERNAME")
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: "Computer"
        name + (number?.let { " ($it)" } ?: "")
    }

    val windowTitle: String get() = "TransferingFileByAir" + (number?.let { " ($it)" } ?: "")
}
