package com.kotlistine.transferingfilebyair.feature.shelf.presentation.di

import com.kotlistine.transferingfilebyair.feature.shelf.domain.UnattendedReceiver
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
            board = get(),
            unattended = getOrNull(),
            videoEngine = getOrNull(),
            hands = getOrNull(),
        )
    }
}

/** Qualifier of the app-wide CoroutineScope (main thread) that file transfers run in; bound by the platform shell. */
const val TransfersScope = "transfers"

/**
 * Takes in the peer's files while no shelf screen is open. Created at start, so it listens before any screen does;
 * added by the platform shell, which binds the app-wide [TransfersScope] it runs in.
 */
val unattendedReceiverModule = module {
    single(createdAtStart = true) {
        UnattendedReceiver(
            peer = get(),
            board = get(),
            keepAlive = getOrNull() ?: TransferKeepAlive.None,
            scope = get(named(TransfersScope)),
        )
    }
}
