package com.kotlistine.transferingfilebyair.feature.shelf.data.di

import com.kotlistine.transferingfilebyair.feature.shelf.data.database.createShelfDatabase
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.AndroidFileMetadataSource
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.FileMetadataSource
import org.koin.dsl.module

internal actual val platformShelfDataModule = module {
    single { createShelfDatabase(context = get()) }
    single<FileMetadataSource> { AndroidFileMetadataSource(context = get()) }
}
