package com.kotlistine.transferingfilebyair.shared

import com.kotlistine.transferingfilebyair.core.transfer.data.di.transferDataModule
import com.kotlistine.transferingfilebyair.feature.shelf.data.di.shelfDataModule
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.di.shelfPresentationModule
import org.koin.core.module.Module

/** Platform-independent Koin modules. Platform shells add their own (e.g. the Nearby transport) on top. */
val appModules: List<Module> = listOf(
    transferDataModule,
    shelfDataModule,
    shelfPresentationModule,
)
