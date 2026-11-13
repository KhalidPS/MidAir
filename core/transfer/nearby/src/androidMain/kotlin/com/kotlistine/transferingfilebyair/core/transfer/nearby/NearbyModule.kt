package com.kotlistine.transferingfilebyair.core.transfer.nearby

import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier of the phones' Nearby [PeerLink] and its [LinkGeometry]; the app shell puts them behind the app's link. */
const val NearbyLink = "nearby"

/** The Android link to the phone next door, and how its screen sits (remembered). Added by the Android app shell. */
val nearbyModule = module {
    single { PeerMemory(context = get()) }
    single<LinkGeometry>(named(NearbyLink)) { get<PeerMemory>() }
    single<PeerLink>(named(NearbyLink)) { NearbyPeerLink(context = get(), memory = get()) }
}
