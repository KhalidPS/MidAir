package com.kotlistine.midair.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.kotlistine.midair.core.transfer.domain.ExitEdge
import com.kotlistine.midair.core.transfer.domain.ScreenDensity
import java.awt.GraphicsEnvironment

/** The window's width when it stands against the screen edge the phone is at. */
private val DockedWidth = 480.dp

/**
 * A phone's screen height, roughly (a 6.1–6.7" phone is 140–160 mm). The laptop can't measure the phone; it assumes
 * this, with the phone standing beside the screen, bottoms level, as in the reference video.
 */
private const val AssumedPhoneScreenMm = 150f

/**
 * Where the phone stands: beside the laptop screen's left or right edge. The window docks against that edge, full
 * height, so the window's edge is the screen's edge the card crosses, as in the reference video.
 */
internal object PhonePlacement {

    /** Moves the window flush against [side] of the screen's work area (above the taskbar), full height. */
    fun dock(window: WindowState, side: ExitEdge) {
        val area = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val x = if (side == ExitEdge.RIGHT) area.x + area.width - DockedWidth.value.toInt() else area.x
        window.size = DpSize(DockedWidth, area.height.dp)
        window.position = WindowPosition(x.dp, area.y.dp)
    }

    /**
     * How far the phone's screen top sits below the window's, if the phone's bottom is level with the bottom of the
     * screen's work area. Never above the window's top.
     */
    fun phoneTopBelowWindowMm(density: ScreenDensity): Float {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val area = environment.maximumWindowBounds
        // The work area is in the desktop's scaled units; the density is in physical pixels.
        val scale = environment.defaultScreenDevice.defaultConfiguration.defaultTransform.scaleY
        val windowHeightMm = density.yPxToMm((area.height * scale).toFloat())
        return (windowHeightMm - AssumedPhoneScreenMm).coerceAtLeast(0f)
    }
}
