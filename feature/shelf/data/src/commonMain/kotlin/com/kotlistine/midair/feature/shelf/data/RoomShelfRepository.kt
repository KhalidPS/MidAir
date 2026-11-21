package com.kotlistine.midair.feature.shelf.data

import com.kotlistine.midair.core.domain.shelf.ShelfItem
import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult
import com.kotlistine.midair.core.domain.util.Result
import com.kotlistine.midair.feature.shelf.data.database.ShelfItemDao
import com.kotlistine.midair.feature.shelf.data.database.ShelfItemEntity
import com.kotlistine.midair.feature.shelf.data.file.FileMetadataSource
import com.kotlistine.midair.feature.shelf.domain.ShelfRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class RoomShelfRepository(
    private val dao: ShelfItemDao,
    private val files: FileMetadataSource,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ShelfRepository {

    override fun observeItems(): Flow<List<ShelfItem>> =
        dao.observeAll().map { entities -> entities.map { it.toShelfItem() } }

    override suspend fun addFile(uri: String): EmptyResult<DataError.Local> {
        when (val exists = databaseCall { dao.existsByUri(uri) }) {
            is Result.Failure -> return exists
            is Result.Success -> if (exists.data) return Result.Success(Unit)
        }
        val metadata = when (val read = files.read(uri)) {
            is Result.Failure -> return read
            is Result.Success -> read.data
        }
        val retained = files.retainAccess(uri)
        if (retained is Result.Failure) return retained

        val entity = ShelfItemEntity(
            uri = uri,
            name = metadata.name,
            mimeType = metadata.mimeType,
            sizeBytes = metadata.sizeBytes,
            addedAtEpochMillis = nowEpochMillis(),
            widthPx = metadata.widthPx,
            heightPx = metadata.heightPx,
            textPreview = metadata.textPreview,
        )
        val inserted = databaseCall { dao.insert(entity) }
        if (inserted is Result.Failure) {
            files.releaseAccess(uri)
            return inserted
        }
        return Result.Success(Unit)
    }

    override suspend fun removeItem(id: String): EmptyResult<DataError.Local> {
        val rowId = id.toLongOrNull() ?: return Result.Failure(DataError.Local.FILE_NOT_FOUND)
        val entity = when (val found = databaseCall { dao.getById(rowId) }) {
            is Result.Failure -> return found
            is Result.Success -> found.data ?: return Result.Failure(DataError.Local.FILE_NOT_FOUND)
        }
        val deleted = databaseCall { dao.deleteById(rowId) }
        if (deleted is Result.Success) files.releaseAccess(entity.uri)
        return deleted
    }

    override suspend fun clear(): EmptyResult<DataError.Local> {
        val uris = when (val all = databaseCall { dao.getAllUris() }) {
            is Result.Failure -> return all
            is Result.Success -> all.data
        }
        val deleted = databaseCall { dao.deleteAll() }
        if (deleted is Result.Success) uris.forEach { files.releaseAccess(it) }
        return deleted
    }

    private inline fun <T> databaseCall(block: () -> T): Result<T, DataError.Local> =
        try {
            Result.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // "database or disk is full" is SQLite's own text for SQLITE_FULL (code 13) in every driver.
            val full = e.message?.contains("disk is full", ignoreCase = true) == true
            Result.Failure(if (full) DataError.Local.DISK_FULL else DataError.Local.UNKNOWN)
        }
}

private fun ShelfItemEntity.toShelfItem() = ShelfItem(
    id = id.toString(),
    name = name,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    addedAtEpochMillis = addedAtEpochMillis,
    uri = uri,
    widthPx = widthPx,
    heightPx = heightPx,
    textPreview = textPreview,
)
