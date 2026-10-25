package com.kotlistine.transferingfilebyair.core.media.di

import org.koin.core.module.Module
import org.koin.dsl.module

/** Provides a new [com.kotlistine.transferingfilebyair.core.media.VideoEngine] to each consumer. */
internal expect val platformMediaModule: Module

val mediaModule = module {
    includes(platformMediaModule)
}
