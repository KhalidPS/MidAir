package com.kotlistine.midair.feature.pairing.presentation.lineup

sealed interface LineUpAction {
    /** A finished swipe crossed this screen's edge. */
    data class OnEdgeCrossed(val crossing: SwipeCrossingPx) : LineUpAction

    /** Handled by the root: leave, keeping what was measured. */
    data object OnDoneClick : LineUpAction

    /** Handled by the root: leave without lining up (slides keep the defaults). */
    data object OnSkipClick : LineUpAction
}
