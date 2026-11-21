package com.kotlistine.midair.feature.simulator

sealed interface SimulatorEvent {
    data object TwinsReset : SimulatorEvent
}
