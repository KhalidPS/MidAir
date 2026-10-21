package com.kotlistine.transferingfilebyair

import android.app.Application
import com.kotlistine.transferingfilebyair.core.transfer.nearby.nearbyModule
import com.kotlistine.transferingfilebyair.shared.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AirApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AirApplication)
            // The Android link to the device next door joins the shared modules.
            modules(appModules + nearbyModule)
        }
    }
}
