package com.kotlistine.midair.desktop

import com.kotlistine.midair.core.transfer.domain.LinkGeometry
import com.kotlistine.midair.core.transfer.domain.PeerLink
import com.kotlistine.midair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.midair.core.transfer.lan.DiskLanFiles
import com.kotlistine.midair.core.transfer.lan.JmdnsDiscovery
import com.kotlistine.midair.core.transfer.lan.LanMemory
import com.kotlistine.midair.core.transfer.lan.LanPeerLink
import com.kotlistine.midair.feature.shelf.presentation.di.TransfersScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.swing.Swing
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File

/** What only the desktop shell provides: the transfers' app-wide scope, and the link to the phone over the LAN. */
internal val desktopModule = module {
    single(named(TransfersScope)) { CoroutineScope(SupervisorJob() + Dispatchers.Swing) }
    // A desktop app stays alive while its window is open; nothing extra keeps it running.
    single { TransferKeepAlive.None }

    single { LanMemory(File(DesktopInstance.dataFolder, "link.properties")) }
    single<LinkGeometry> { get<LanMemory>() }
    single { JmdnsDiscovery() }
    single { LanPeerLink(
        memory = get(),
        discovery = get<JmdnsDiscovery>(),
        files = DiskLanFiles(File(DesktopInstance.dataFolder, "incoming")),
        ownName = DesktopInstance.deviceName,
        // The laptop says where the phone stands (its Phone menu); the phone takes it.
        ownsPlacement = true,
        log = { println("AirLan: $it") },
    ) }
    single<PeerLink> { get<LanPeerLink>() }
}
