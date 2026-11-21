package com.kotlistine.midair.feature.shelf.data.gesture

import java.util.concurrent.TimeUnit

private const val ConsentStore = "Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\webcam"

/**
 * The switches in Windows' Settings → Privacy & security → Camera that a desktop app like this one has to get past:
 * camera access for the whole device, for apps, and for desktop apps. Any of them set to "Deny" keeps the webcam from
 * opening, with no prompt; there's no way for the app to ask, only to send the user to the page.
 */
internal object CameraPrivacy {

    // Registry keys aren't case-sensitive: the same path works under both roots.
    private val switches = listOf(
        "HKLM\\$ConsentStore",
        "HKCU\\$ConsentStore",
        "HKCU\\$ConsentStore\\NonPackaged",
    )

    /** Whether a privacy switch blocks the webcam. False off Windows, or when it can't be told. */
    fun isBlockedBySystem(): Boolean {
        if ("win" !in System.getProperty("os.name").lowercase()) return false
        return switches.any { readValue(it).equals("Deny", ignoreCase = true) }
    }

    /** The switch's `Value` ("Allow" or "Deny"), or null when it isn't set. */
    private fun readValue(key: String): String? = runCatching {
        val process = ProcessBuilder("reg", "query", key, "/v", "Value").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        output.lineSequence().firstOrNull { "REG_SZ" in it }?.substringAfter("REG_SZ")?.trim()
    }.getOrNull()
}
