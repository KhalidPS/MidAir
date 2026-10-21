package com.kotlistine.transferingfilebyair.feature.simulator.di

import com.kotlistine.transferingfilebyair.feature.simulator.SimulatorViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Loaded on top of the app's modules by the debug-only simulator entry point. */
val simulatorModule = module {
    viewModel { SimulatorViewModel(shelf = get(), linkFactory = get()) }
}
