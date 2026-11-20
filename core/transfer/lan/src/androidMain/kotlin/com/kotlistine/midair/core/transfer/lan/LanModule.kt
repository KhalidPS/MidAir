package com.kotlistine.midair.core.transfer.lan

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.koin.dsl.module
import java.io.File

/**
 * The phone's link to a laptop over the local network, and what it remembers. Added by the Android app shell, which
 * puts it behind the same screens as the phones' Nearby link.
 */
val lanModule = module {
    single { LanMemory(File(get<Context>().filesDir, "lan-link.properties")) }
    single {
        val context = get<Context>()
        LanPeerLink(
            memory = get(),
            discovery = NsdDiscovery(context),
            files = ContentLanFiles(context),
            ownName = deviceName(context),
            log = { Log.i("AirLan", it) },
        )
    }
}

/** The name people gave their phone ("Khalid's Pixel"), or its model: what the laptop shows. */
private fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
