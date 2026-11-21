package com.kotlistine.midair.feature.shelf.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal fun createShelfDatabase(context: Context, name: String = ShelfDatabase.NAME): ShelfDatabase {
    val appContext = context.applicationContext
    return Room.databaseBuilder<ShelfDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(name).absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
