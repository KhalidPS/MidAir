package com.kotlistine.transferingfilebyair.feature.shelf.data.di

import com.kotlistine.transferingfilebyair.feature.shelf.data.RoomShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.data.database.ShelfDatabase
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/** Provides [ShelfDatabase] and a FileMetadataSource for the current platform. */
internal expect val platformShelfDataModule: Module

val shelfDataModule = module {
    includes(platformShelfDataModule)
    single { get<ShelfDatabase>().shelfItemDao() }
    single<ShelfRepository> { RoomShelfRepository(dao = get(), files = get()) }
}
