package com.kotlistine.transferingfilebyair.feature.pairing.presentation.di

import com.kotlistine.transferingfilebyair.feature.pairing.presentation.PairingViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs a PeerLink binding, which the platform shell provides (Nearby on Android). */
val pairingPresentationModule = module {
    viewModel { PairingViewModel(link = get()) }
}
