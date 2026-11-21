package com.kotlistine.midair.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How fast the app opens, without and with its Baseline Profile, and how smoothly the shelf scrolls (frame times:
 * at 60 fps every frame has 16.7 ms). Run on a phone with `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`;
 * put a dozen files on the shelf first so there is something to scroll.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ShelfBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun shelfScroll() = rule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = 5,
        setupBlock = { openShelf() },
    ) {
        scrollShelf()
    }

    private fun startup(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = TargetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        openShelf()
    }
}
