package com.kotlistine.transferingfilebyair.core.transfer.nearby

import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import org.koin.dsl.module

/** The Android link to the device next door, and how its screen sits (remembered). Added by the Android app shell. */
val nearbyModule = module {
    single { PeerMemory(context = get()) }
    single<LinkGeometry> { get<PeerMemory>() }
    single<PeerLink> { NearbyPeerLink(context = get(), memory = get()) }
}
