package com.kotlistine.midair.feature.shelf.data.di

import com.kotlistine.midair.feature.shelf.data.database.createShelfDatabase
import com.kotlistine.midair.feature.shelf.data.file.AndroidFileMetadataSource
import com.kotlistine.midair.feature.shelf.data.file.AndroidReceivedFileStore
import com.kotlistine.midair.feature.shelf.data.file.FileMetadataSource
import com.kotlistine.midair.feature.shelf.data.file.ReceivedFileStore
import com.kotlistine.midair.feature.shelf.data.gesture.CameraHandTracker
import com.kotlistine.midair.feature.shelf.domain.gesture.HandTracker
import org.koin.dsl.module

internal actual val platformShelfDataModule = module {
    single { createShelfDatabase(context = get()) }
    single<FileMetadataSource> { AndroidFileMetadataSource(context = get()) }
    single<ReceivedFileStore> { AndroidReceivedFileStore(context = get()) }
    // Air gestures: the front camera, read by MediaPipe. A platform without one binds none, and the shelf goes without.
    single<HandTracker> { CameraHandTracker(context = get()) }
}
