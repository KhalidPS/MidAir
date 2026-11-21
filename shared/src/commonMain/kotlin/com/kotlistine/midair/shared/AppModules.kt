package com.kotlistine.midair.shared

import com.kotlistine.midair.core.media.di.mediaModule
import com.kotlistine.midair.core.transfer.data.di.transferDataModule
import com.kotlistine.midair.feature.pairing.presentation.di.pairingPresentationModule
import com.kotlistine.midair.feature.shelf.data.di.shelfDataModule
import com.kotlistine.midair.feature.shelf.presentation.di.shelfPresentationModule
import org.koin.core.module.Module

/** Platform-independent Koin modules. Platform shells add their own (e.g. the Nearby transport) on top. */
val appModules: List<Module> = listOf(
    transferDataModule,
    mediaModule,
    pairingPresentationModule,
    shelfDataModule,
    shelfPresentationModule,
)
