package com.kotlistine.midair.feature.shelf.domain

import com.kotlistine.midair.core.domain.shelf.ShelfItem
import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult
import kotlinx.coroutines.flow.Flow

interface ShelfRepository {

    /** Shelf contents, newest first. Emits again on every change. */
    fun observeItems(): Flow<List<ShelfItem>>

    /** Stages the file behind [uri]. Adding a file that is already on the shelf is a no-op success. */
    suspend fun addFile(uri: String): EmptyResult<DataError.Local>

    suspend fun removeItem(id: String): EmptyResult<DataError.Local>

    suspend fun clear(): EmptyResult<DataError.Local>
}
