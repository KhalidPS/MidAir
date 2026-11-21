package com.kotlistine.midair.core.transfer.domain

import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/** Makes a [SimulatedLink] whose message delivery runs (and ends) in the given scope. */
fun interface SimulatedLinkFactory {
    fun create(scope: CoroutineScope): SimulatedLink
}

/** Two devices' transports joined in memory, with adjustable delay: what the Twin Simulator puts between its twins. */
interface SimulatedLink {
    val left: Transport
    val right: Transport

    /** One-way delay of every message, to rehearse a real radio link. */
    var latency: Duration
}
