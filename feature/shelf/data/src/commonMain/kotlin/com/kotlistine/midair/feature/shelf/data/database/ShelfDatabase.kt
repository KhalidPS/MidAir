package com.kotlistine.midair.feature.shelf.data.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(entities = [ShelfItemEntity::class], version = 1)
@ConstructedBy(ShelfDatabaseConstructor::class)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun shelfItemDao(): ShelfItemDao

    companion object {
        const val NAME = "shelf.db"
    }
}

// Room's KSP processor generates the actual implementation for every target.
@Suppress("KotlinNoActualForExpect")
expect object ShelfDatabaseConstructor : RoomDatabaseConstructor<ShelfDatabase> {
    override fun initialize(): ShelfDatabase
}
