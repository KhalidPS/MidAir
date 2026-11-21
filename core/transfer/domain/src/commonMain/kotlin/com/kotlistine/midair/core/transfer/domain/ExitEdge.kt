package com.kotlistine.midair.core.transfer.domain

/** The screen edge a card leaves through. The paired device is assumed to sit beside that edge. */
enum class ExitEdge {
    LEFT,
    RIGHT,
    ;

    /** The receiver's edge the card comes in through when the devices sit side by side. */
    val opposite: ExitEdge
        get() = if (this == LEFT) RIGHT else LEFT
}
