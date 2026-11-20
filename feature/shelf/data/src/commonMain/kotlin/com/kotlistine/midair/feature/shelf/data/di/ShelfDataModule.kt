package com.kotlistine.midair.feature.shelf.data.di

import com.kotlistine.midair.feature.shelf.data.LinkedSlidePeer
import com.kotlistine.midair.feature.shelf.data.RoomShelfRepository
import com.kotlistine.midair.feature.shelf.data.database.ShelfDatabase
import com.kotlistine.midair.feature.shelf.domain.SentItems
import com.kotlistine.midair.feature.shelf.domain.ShelfRepository
import com.kotlistine.midair.feature.shelf.domain.SlidePeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Provides [ShelfDatabase], a FileMetadataSource and a ReceivedFileStore for the current platform, and a HandTracker
 * where the platform can watch for air gestures.
 */
internal expect val platformShelfDataModule: Module

val shelfDataModule = module {
    includes(platformShelfDataModule)
    single { get<ShelfDatabase>().shelfItemDao() }
    single<ShelfRepository> { RoomShelfRepository(dao = get(), files = get()) }
    // App-wide, so a screen opened while a send goes on still hides its item.
    single { SentItems() }
    // The paired device. Needs a PeerLink, which the platform shell provides (Nearby on Android).
    single<SlidePeer> {
        LinkedSlidePeer(
            peerLink = get(),
            repository = get(),
            store = get(),
            geometry = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
