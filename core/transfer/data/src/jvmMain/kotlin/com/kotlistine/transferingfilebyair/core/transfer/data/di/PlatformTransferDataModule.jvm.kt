package com.kotlistine.transferingfilebyair.core.transfer.data.di

import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import org.koin.dsl.module
import java.awt.GraphicsEnvironment
import kotlin.math.hypot

/**
 * A laptop screen's diagonal, in inches, when nothing better is known. The JVM can't read the screen's physical size,
 * so its density is a guess from its pixels; the line-up swipe corrects the rest.
 */
private const val AssumedDiagonalInches = 14f

internal actual val platformTransferDataModule = module {
    single {
        val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
        val dpi = hypot(mode.width.toFloat(), mode.height.toFloat()) / AssumedDiagonalInches
        ScreenDensity(xdpi = dpi, ydpi = dpi)
    }
}
