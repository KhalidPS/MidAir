package com.kotlistine.midair.feature.pairing.presentation.lineup

sealed interface LineUpEvent {
    /** A swipe just lined the screens up: confirm it with a tap of the haptics. */
    data object LinedUp : LineUpEvent
}
