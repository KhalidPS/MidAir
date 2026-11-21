package com.kotlistine.midair.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code the app runs on its way to the shelf and while the shelf scrolls, so it is compiled ahead of
 * time on install: the first launch and the first slides don't stutter while that code is still being interpreted.
 * Run with `./gradlew :androidApp:generateBaselineProfile`; the result lands in androidApp/src/release/generated/baselineProfiles.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TargetPackage, includeInStartupProfile = true) {
        openShelf()
        scrollShelf()
    }
}
