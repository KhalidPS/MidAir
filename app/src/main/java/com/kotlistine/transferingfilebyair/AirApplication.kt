package com.kotlistine.transferingfilebyair

import android.app.Application
import com.kotlistine.transferingfilebyair.core.transfer.nearby.nearbyModule
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.di.TransfersScope
import com.kotlistine.transferingfilebyair.shared.appModules
import com.kotlistine.transferingfilebyair.transfer.ServiceTransferKeepAlive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class AirApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AirApplication)
            // The Android link to the device next door, and what keeps transfers alive, join the shared modules.
            modules(appModules + nearbyModule + transferModule)
        }
    }
}

/** File transfers outlive any one screen: they run in an app-wide scope, kept alive by a foreground service. */
private val transferModule = module {
    single(named(TransfersScope)) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    single<TransferKeepAlive> { ServiceTransferKeepAlive(context = get()) }
}
