package com.kotlistine.transferingfilebyair.feature.simulator

sealed interface SimulatorEvent {
    data object TwinsReset : SimulatorEvent
}
