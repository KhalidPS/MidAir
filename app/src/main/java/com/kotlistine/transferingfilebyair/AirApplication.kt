package com.kotlistine.transferingfilebyair

import android.app.Application
import com.kotlistine.transferingfilebyair.shared.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AirApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AirApplication)
            modules(appModules)
        }
    }
}
