package com.kotlistine.transferingfilebyair.feature.shelf.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfItemDao {

    @Query("SELECT * FROM shelf_items ORDER BY addedAtEpochMillis DESC, id DESC")
    fun observeAll(): Flow<List<ShelfItemEntity>>

    @Query("SELECT * FROM shelf_items WHERE id = :id")
    suspend fun getById(id: Long): ShelfItemEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM shelf_items WHERE uri = :uri)")
    suspend fun existsByUri(uri: String): Boolean

    @Query("SELECT uri FROM shelf_items")
    suspend fun getAllUris(): List<String>

    /** Returns the new row id, or -1 when the uri is already on the shelf. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ShelfItemEntity): Long

    @Query("DELETE FROM shelf_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM shelf_items")
    suspend fun deleteAll()
}
