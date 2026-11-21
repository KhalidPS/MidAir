package com.kotlistine.midair.feature.simulator

import com.kotlistine.midair.core.transfer.domain.SlideLink

data class SimulatorState(
    /** One-way delay of every message between the twins. */
    val latencyMs: Int = 0,
    /** Bezels plus air between the two screens; drawn at its true physical size. */
    val gapMm: Float = SlideLink.DefaultGapMm,
    /** The left twin has been stocked with a copy of the real shelf. */
    val isReady: Boolean = false,
)
