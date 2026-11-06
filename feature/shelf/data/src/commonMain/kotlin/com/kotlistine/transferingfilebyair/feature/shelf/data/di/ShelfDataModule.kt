package com.kotlistine.transferingfilebyair.feature.shelf.data.di

import com.kotlistine.transferingfilebyair.feature.shelf.data.LinkedSlidePeer
import com.kotlistine.transferingfilebyair.feature.shelf.data.RoomShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.data.database.ShelfDatabase
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
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
