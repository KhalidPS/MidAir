package com.kotlistine.midair.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

internal const val TargetPackage = "com.kotlistine.midair"

/**
 * Opens the app on its shelf and waits for it to settle; with items on the shelf, scrolls through them and back, so
 * the grid, its cards and their previews are part of the journey.
 */
internal fun MacrobenchmarkScope.openShelf() {
    pressHome()
    startActivityAndWait()
    // The bottom bar's add button is there once the shelf has drawn.
    device.wait(Until.hasObject(By.desc("Add files")), 5_000)
}

internal fun MacrobenchmarkScope.scrollShelf() {
    val grid = device.findObject(By.scrollable(true)) ?: return
    // Keep clear of the gesture navigation area at the screen's bottom.
    grid.setGestureMargin(device.displayWidth / 5)
    grid.fling(Direction.DOWN)
    device.waitForIdle()
    grid.fling(Direction.UP)
    device.waitForIdle()
}
