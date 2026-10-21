package com.kotlistine.transferingfilebyair.core.transfer.domain

private const val MmPerInch = 25.4f

/**
 * Physical pixel density of a screen. Both devices describe a card in millimetres, so it keeps the same physical size
 * (and lines up across the gap) on screens with different pixel densities.
 */
data class ScreenDensity(val xdpi: Float, val ydpi: Float) {
    fun xPxToMm(px: Float): Float = px / xdpi * MmPerInch
    fun yPxToMm(px: Float): Float = px / ydpi * MmPerInch
    fun xMmToPx(mm: Float): Float = mm / MmPerInch * xdpi
    fun yMmToPx(mm: Float): Float = mm / MmPerInch * ydpi
}
