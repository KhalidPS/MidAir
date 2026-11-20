package com.kotlistine.midair.feature.shelf.data.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kotlistine.midair.feature.shelf.data.file.desktopAppDataFolder
import kotlinx.coroutines.Dispatchers
import java.io.File

internal fun createShelfDatabase(name: String = ShelfDatabase.NAME): ShelfDatabase =
    Room.databaseBuilder<ShelfDatabase>(name = File(desktopAppDataFolder(), name).absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
