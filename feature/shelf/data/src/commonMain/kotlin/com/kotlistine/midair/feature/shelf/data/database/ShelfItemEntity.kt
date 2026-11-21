package com.kotlistine.midair.feature.shelf.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shelf_items",
    indices = [Index(value = ["uri"], unique = true)],
)
data class ShelfItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val addedAtEpochMillis: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val textPreview: String?,
)
