package com.kotlistine.midair.feature.pairing.presentation.di

import com.kotlistine.midair.feature.pairing.presentation.PairingViewModel
import com.kotlistine.midair.feature.pairing.presentation.lineup.LineUpViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs a PeerLink binding, which the platform shell provides (Nearby on Android). */
val pairingPresentationModule = module {
    viewModel { PairingViewModel(link = get(), invites = getOrNull()) }
    // Also needs the LinkGeometry and ScreenDensity bound by the transfer data module.
    viewModel { LineUpViewModel(link = get(), geometry = get(), density = get()) }
}
