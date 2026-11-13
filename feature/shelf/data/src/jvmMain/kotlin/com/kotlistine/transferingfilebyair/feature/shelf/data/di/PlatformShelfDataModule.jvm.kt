package com.kotlistine.transferingfilebyair.feature.shelf.data.di

import com.kotlistine.transferingfilebyair.feature.shelf.data.database.createShelfDatabase
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.DesktopFileMetadataSource
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.DesktopReceivedFileStore
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.FileMetadataSource
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.ReceivedFileStore
import org.koin.dsl.module

/** No HandTracker: a laptop's camera faces the user, not a hand waving over a phone, so air gestures stay off. */
internal actual val platformShelfDataModule = module {
    single { createShelfDatabase() }
    single<FileMetadataSource> { DesktopFileMetadataSource() }
    single<ReceivedFileStore> { DesktopReceivedFileStore() }
}
