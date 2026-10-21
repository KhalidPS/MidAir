package com.kotlistine.transferingfilebyair.feature.shelf.presentation.di

import com.kotlistine.transferingfilebyair.feature.shelf.presentation.ShelfViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val shelfPresentationModule = module {
    viewModelOf(::ShelfViewModel)
}
