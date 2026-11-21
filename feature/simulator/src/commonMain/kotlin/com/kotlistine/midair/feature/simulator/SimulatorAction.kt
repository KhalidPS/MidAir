package com.kotlistine.midair.feature.simulator

sealed interface SimulatorAction {
    data class OnLatencyChange(val latencyMs: Int) : SimulatorAction
    data class OnGapChange(val gapMm: Float) : SimulatorAction

    /** Left twin back to a copy of the real shelf, right twin empty. */
    data object OnResetClick : SimulatorAction
}
