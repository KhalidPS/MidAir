package com.kotlistine.transferingfilebyair.feature.shelf.presentation.di

import com.kotlistine.transferingfilebyair.feature.shelf.presentation.ShelfViewModel
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val shelfPresentationModule = module {
    viewModel {
        ShelfViewModel(
            repository = get(),
            density = get(),
            peer = get(),
            transfers = getOrNull(named(TransfersScope)),
            keepAlive = getOrNull() ?: TransferKeepAlive.None,
        )
    }
}

/** Qualifier of the app-wide CoroutineScope (main thread) that file transfers run in; bound by the platform shell. */
const val TransfersScope = "transfers"
