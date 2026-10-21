package com.kotlistine.transferingfilebyair.feature.shelf.presentation.di

import com.kotlistine.transferingfilebyair.feature.shelf.presentation.ShelfViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val shelfPresentationModule = module {
    viewModel { ShelfViewModel(repository = get(), density = get(), peer = get()) }
}
