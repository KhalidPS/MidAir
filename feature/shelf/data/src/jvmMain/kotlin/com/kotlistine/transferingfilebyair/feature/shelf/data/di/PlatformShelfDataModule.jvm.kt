package com.kotlistine.transferingfilebyair.feature.shelf.data.di

import com.kotlistine.transferingfilebyair.feature.shelf.data.database.createShelfDatabase
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.DesktopFileMetadataSource
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.DesktopReceivedFileStore
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.FileMetadataSource
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.ReceivedFileStore
import com.kotlistine.transferingfilebyair.feature.shelf.data.gesture.WebcamHandTracker
import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.HandTracker
import org.koin.dsl.module

/** The webcam faces the user: a fist or an open hand held up toward the screen is read like on the phone. */
internal actual val platformShelfDataModule = module {
    single { createShelfDatabase() }
    single<FileMetadataSource> { DesktopFileMetadataSource() }
    single<ReceivedFileStore> { DesktopReceivedFileStore() }
    single<HandTracker> { WebcamHandTracker() }
}
