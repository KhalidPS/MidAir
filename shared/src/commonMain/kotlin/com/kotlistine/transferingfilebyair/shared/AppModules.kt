package com.kotlistine.transferingfilebyair.shared

import com.kotlistine.transferingfilebyair.feature.shelf.shelfModule
import org.koin.core.module.Module

/** Platform-independent Koin modules. Platform shells add their own (e.g. the Nearby transport) on top. */
val appModules: List<Module> = listOf(
    shelfModule,
)
