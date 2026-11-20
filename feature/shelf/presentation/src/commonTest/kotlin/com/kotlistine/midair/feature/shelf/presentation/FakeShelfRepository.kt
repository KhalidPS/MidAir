package com.kotlistine.midair.feature.shelf.presentation

import com.kotlistine.midair.core.domain.shelf.ShelfItem
import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult
import com.kotlistine.midair.core.domain.util.Result
import com.kotlistine.midair.feature.shelf.domain.ShelfRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeShelfRepository : ShelfRepository {
    val items = MutableStateFlow<List<ShelfItem>>(emptyList())

    /** Uris that fail to be added, with the error they fail with. */
    val failingUris = mutableMapOf<String, DataError.Local>()
    var removeFailure: DataError.Local? = null
    var clearFailure: DataError.Local? = null

    private var nextId = 1

    override fun observeItems(): Flow<List<ShelfItem>> = items

    override suspend fun addFile(uri: String): EmptyResult<DataError.Local> {
        failingUris[uri]?.let { return Result.Failure(it) }
        items.update { listOf(item(id = (nextId++).toString(), uri = uri)) + it }
        return Result.Success(Unit)
    }

    override suspend fun removeItem(id: String): EmptyResult<DataError.Local> {
        removeFailure?.let { return Result.Failure(it) }
        items.update { list -> list.filterNot { it.id == id } }
        return Result.Success(Unit)
    }

    override suspend fun clear(): EmptyResult<DataError.Local> {
        clearFailure?.let { return Result.Failure(it) }
        items.value = emptyList()
        return Result.Success(Unit)
    }

    companion object {
        fun item(
            id: String,
            uri: String = "content://file/$id",
            name: String = "file-$id.pdf",
            mimeType: String = "application/pdf",
        ) = ShelfItem(
            id = id,
            name = name,
            mimeType = mimeType,
            sizeBytes = 16_000,
            addedAtEpochMillis = 0,
            uri = uri,
        )
    }
}
