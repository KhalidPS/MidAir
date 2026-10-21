package com.kotlistine.transferingfilebyair.feature.simulator

import com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Every file the simulator knows about, by item id on either twin. A twin "receives" a file by looking up what the
 * other twin sent, standing in for the bytes a real transfer would carry.
 */
internal class SimulatedStorage {
    private val files = HashMap<String, ShelfItem>()

    fun register(item: ShelfItem) {
        files[item.id] = item
    }

    operator fun get(id: String): ShelfItem? = files[id]
}

/**
 * One twin's shelf, held in memory so the twins (and the real shelf) never share state. Ids carry [idPrefix] so
 * they stay unique across both twins.
 */
internal class InMemoryShelfRepository(
    private val idPrefix: String,
    private val storage: SimulatedStorage,
    private val nowEpochMillis: () -> Long,
    /** Reads a picked file's details (through the real shelf, which knows how). */
    private val importFile: suspend (uri: String) -> Result<ShelfItem, DataError.Local>,
) : ShelfRepository {

    private val items = MutableStateFlow<List<ShelfItem>>(emptyList())
    private var nextId = 1

    override fun observeItems(): Flow<List<ShelfItem>> = items

    override suspend fun addFile(uri: String): EmptyResult<DataError.Local> {
        if (items.value.any { it.uri == uri }) return Result.Success(Unit)
        return when (val imported = importFile(uri)) {
            is Result.Failure -> imported
            is Result.Success -> {
                add(imported.data)
                Result.Success(Unit)
            }
        }
    }

    override suspend fun removeItem(id: String): EmptyResult<DataError.Local> {
        if (items.value.none { it.id == id }) return Result.Failure(DataError.Local.FILE_NOT_FOUND)
        items.update { list -> list.filterNot { it.id == id } }
        return Result.Success(Unit)
    }

    override suspend fun clear(): EmptyResult<DataError.Local> {
        items.value = emptyList()
        return Result.Success(Unit)
    }

    /** Puts a copy of [file] on this shelf as the newest item and returns its id here. */
    fun add(file: ShelfItem): String {
        val copy = file.copy(id = "$idPrefix${nextId++}", addedAtEpochMillis = nowEpochMillis())
        storage.register(copy)
        items.update { listOf(copy) + it }
        return copy.id
    }

    /** Replaces everything with copies of [files], keeping their order and times. */
    fun reset(files: List<ShelfItem>) {
        items.value = files.map { file ->
            file.copy(id = "$idPrefix${nextId++}").also(storage::register)
        }
    }
}
