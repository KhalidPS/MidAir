package com.kotlistine.midair.core.transfer.data.di

import com.kotlistine.midair.core.transfer.data.LoopbackLink
import com.kotlistine.midair.core.transfer.domain.ScreenDensity
import com.kotlistine.midair.core.transfer.domain.SimulatedLinkFactory
import com.kotlistine.midair.core.transfer.domain.TransferBoard
import org.koin.core.module.Module
import org.koin.dsl.module

/** Provides this device's [ScreenDensity]. */
internal expect val platformTransferDataModule: Module

val transferDataModule = module {
    includes(platformTransferDataModule)
    factory { SimulatedLinkFactory { scope -> LoopbackLink(scope) } }
    // Every file moving to or from the paired device, app-wide: the shelf and the notification both show it.
    single { TransferBoard() }
}
