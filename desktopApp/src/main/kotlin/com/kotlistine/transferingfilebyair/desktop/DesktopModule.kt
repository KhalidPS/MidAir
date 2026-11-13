package com.kotlistine.transferingfilebyair.desktop

import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.di.TransfersScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.swing.Swing
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** What only the desktop shell provides: the transfers' app-wide scope, and the link to the phone. */
internal val desktopModule = module {
    single(named(TransfersScope)) { CoroutineScope(SupervisorJob() + Dispatchers.Swing) }
    // A desktop app stays alive while its window is open; nothing extra keeps it running.
    single { TransferKeepAlive.None }
    single { OfflinePeerLink() }
    single<PeerLink> { get<OfflinePeerLink>() }
    single<LinkGeometry> { get<OfflinePeerLink>() }
}
