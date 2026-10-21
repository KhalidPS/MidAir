package com.kotlistine.transferingfilebyair.feature.shelf.data

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.feature.shelf.data.database.ShelfItemDao
import com.kotlistine.transferingfilebyair.feature.shelf.data.database.ShelfItemEntity
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.FileMetadata
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.FileMetadataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory stand-in for Room with the same ordering and unique-uri semantics. */
class FakeShelfItemDao : ShelfItemDao {
    private val rows = MutableStateFlow<List<ShelfItemEntity>>(emptyList())
    private var nextId = 1L

    /** When set, every call throws it, like a failing database. */
    var failure: Exception? = null

    /** When set, only [insert] throws it, after every earlier step of an add has succeeded. */
    var insertFailure: Exception? = null

    /** When set, only [deleteById] and [deleteAll] throw it, after the rows to delete were read. */
    var deleteFailure: Exception? = null

    val all: List<ShelfItemEntity> get() = rows.value

    override fun observeAll(): Flow<List<ShelfItemEntity>> =
        rows.map { list -> list.sortedWith(compareByDescending<ShelfItemEntity> { it.addedAtEpochMillis }.thenByDescending { it.id }) }

    override suspend fun getById(id: Long): ShelfItemEntity? = guarded { rows.value.firstOrNull { it.id == id } }

    override suspend fun existsByUri(uri: String): Boolean = guarded { rows.value.any { it.uri == uri } }

    override suspend fun getAllUris(): List<String> = guarded { rows.value.map { it.uri } }

    override suspend fun insert(item: ShelfItemEntity): Long = guarded {
        insertFailure?.let { throw it }
        if (rows.value.any { it.uri == item.uri }) return@guarded -1L
        val id = nextId++
        rows.update { it + item.copy(id = id) }
        id
    }

    override suspend fun deleteById(id: Long) = guarded {
        deleteFailure?.let { throw it }
        rows.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun deleteAll() = guarded {
        deleteFailure?.let { throw it }
        rows.value = emptyList()
    }

    private inline fun <T> guarded(block: () -> T): T {
        failure?.let { throw it }
        return block()
    }
}

class FakeFileMetadataSource : FileMetadataSource {
    val files = mutableMapOf<String, FileMetadata>()
    val retained = mutableSetOf<String>()
    var retainFailure: DataError.Local? = null

    /** How many times each uri was read. */
    val reads = mutableMapOf<String, Int>()

    override suspend fun read(uri: String): Result<FileMetadata, DataError.Local> {
        reads[uri] = (reads[uri] ?: 0) + 1
        return files[uri]?.let { Result.Success(it) } ?: Result.Failure(DataError.Local.FILE_NOT_FOUND)
    }

    override suspend fun retainAccess(uri: String): EmptyResult<DataError.Local> {
        retainFailure?.let { return Result.Failure(it) }
        retained += uri
        return Result.Success(Unit)
    }

    override suspend fun releaseAccess(uri: String) {
        retained -= uri
    }
}
