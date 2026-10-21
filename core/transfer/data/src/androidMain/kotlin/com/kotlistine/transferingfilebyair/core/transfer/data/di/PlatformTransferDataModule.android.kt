package com.kotlistine.transferingfilebyair.core.transfer.data.di

import android.content.Context
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import org.koin.dsl.module

internal actual val platformTransferDataModule = module {
    single {
        // The physical densities, not the rounded density bucket: a card must keep its size in millimetres.
        val metrics = get<Context>().resources.displayMetrics
        ScreenDensity(xdpi = metrics.xdpi, ydpi = metrics.ydpi)
    }
}
