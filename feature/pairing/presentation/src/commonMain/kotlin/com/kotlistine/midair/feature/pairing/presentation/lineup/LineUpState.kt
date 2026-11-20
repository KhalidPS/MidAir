package com.kotlistine.midair.feature.pairing.presentation.lineup

import com.kotlistine.midair.core.transfer.domain.ExitEdge

enum class LineUpPhase {
    /** Waiting for one finger to swipe from one screen onto the other. */
    WAITING,

    /** This phone felt its half of the swipe; waiting for the other phone's half. */
    MEASURING,

    /** The swipe didn't make it from one screen onto the other: try again. */
    MISSED,
    LINED_UP,
}

data class LineUpState(
    val phase: LineUpPhase = LineUpPhase.WAITING,
    /** The other phone's name ("Khalid's Pixel"). */
    val peerName: String? = null,
    /** Once lined up: this screen's edge that faces the other phone. */
    val peerSide: ExitEdge? = null,
    /** Once lined up: the distance between the two pictures (bezels plus the air between the phones). */
    val gapMm: Float? = null,
    /** Once lined up: how much lower the other phone's screen sits (negative: higher). */
    val peerLowerByMm: Float? = null,
)
