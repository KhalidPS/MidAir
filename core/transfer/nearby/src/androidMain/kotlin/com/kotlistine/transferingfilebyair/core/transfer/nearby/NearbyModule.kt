package com.kotlistine.transferingfilebyair.core.transfer.nearby

import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import org.koin.dsl.module

/** The Android link to the device next door. Added by the Android app shell. */
val nearbyModule = module {
    single<PeerLink> { NearbyPeerLink(context = get()) }
}
