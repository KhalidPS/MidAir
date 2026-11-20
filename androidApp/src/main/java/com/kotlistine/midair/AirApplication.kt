package com.kotlistine.midair

import android.app.Application
import com.kotlistine.midair.core.transfer.data.CombinedLinkGeometry
import com.kotlistine.midair.core.transfer.data.CombinedPeerLink
import com.kotlistine.midair.core.transfer.domain.InviteLink
import com.kotlistine.midair.core.transfer.domain.LinkGeometry
import com.kotlistine.midair.core.transfer.domain.PeerLink
import com.kotlistine.midair.core.transfer.lan.LanMemory
import com.kotlistine.midair.core.transfer.lan.LanPeerLink
import com.kotlistine.midair.core.transfer.lan.lanModule
import com.kotlistine.midair.core.transfer.nearby.NearbyLink
import com.kotlistine.midair.core.transfer.nearby.nearbyModule
import com.kotlistine.midair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.midair.feature.shelf.presentation.di.TransfersScope
import com.kotlistine.midair.feature.shelf.presentation.di.unattendedReceiverModule
import com.kotlistine.midair.shared.appModules
import com.kotlistine.midair.transfer.ServiceTransferKeepAlive
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
            // The Android links to the device next door, and what keeps transfers alive, join the shared modules.
            modules(appModules + nearbyModule + lanModule + linkModule + transferModule + unattendedReceiverModule)
        }
    }
}

/**
 * The app's one link: phones over Nearby, laptops over the local network. While no laptop is linked, it is the Nearby
 * link exactly; see [CombinedPeerLink].
 */
private val linkModule = module {
    single {
        CombinedPeerLink(
            primary = get(named(NearbyLink)),
            secondary = get<LanPeerLink>(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    single<PeerLink> { get<CombinedPeerLink>() }
    // Scanning a laptop's QR code pairs with it.
    single<InviteLink> { get<CombinedPeerLink>() }
    single<LinkGeometry> {
        CombinedLinkGeometry(
            primary = get(named(NearbyLink)),
            secondary = get<LanMemory>(),
            usesSecondary = get<CombinedPeerLink>().usesSecondary,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}

/** File transfers outlive any one screen: they run in an app-wide scope, kept alive by a foreground service. */
private val transferModule = module {
    single(named(TransfersScope)) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    single<TransferKeepAlive> { ServiceTransferKeepAlive(context = get()) }
}
