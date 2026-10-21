package com.kotlistine.transferingfilebyair.core.transfer.data.di

import com.kotlistine.transferingfilebyair.core.transfer.data.LoopbackLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.core.transfer.domain.SimulatedLinkFactory
import org.koin.core.module.Module
import org.koin.dsl.module

/** Provides this device's [ScreenDensity]. */
internal expect val platformTransferDataModule: Module

val transferDataModule = module {
    includes(platformTransferDataModule)
    factory { SimulatedLinkFactory { scope -> LoopbackLink(scope) } }
}
