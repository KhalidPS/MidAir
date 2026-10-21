package com.kotlistine.transferingfilebyair.feature.simulator

import com.kotlistine.transferingfilebyair.core.domain.shelf.ShelfItem
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.SimulatedLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

fun file(id: String, uri: String = "content://file/$id", name: String = "file-$id.jpg") = ShelfItem(
    id = id,
    name = name,
    mimeType = "image/jpeg",
    sizeBytes = 1_000,
    addedAtEpochMillis = 5,
    uri = uri,
)

/** Stands in for the real shelf: picked uris become items named after them. */
class FakeShelf : ShelfRepository {
    val items = MutableStateFlow<List<ShelfItem>>(emptyList())
    var addFailure: DataError.Local? = null

    override fun observeItems(): Flow<List<ShelfItem>> = items

    override suspend fun addFile(uri: String): EmptyResult<DataError.Local> {
        addFailure?.let { return Result.Failure(it) }
        items.update { listOf(file(id = "real-${it.size + 1}", uri = uri)) + it }
        return Result.Success(Unit)
    }

    override suspend fun removeItem(id: String): EmptyResult<DataError.Local> = Result.Success(Unit)

    override suspend fun clear(): EmptyResult<DataError.Local> = Result.Success(Unit)
}

class FakeLink : SimulatedLink {
    override val left: Transport = SilentTransport()
    override val right: Transport = SilentTransport()
    override var latency: Duration = Duration.ZERO
}

private class SilentTransport : Transport {
    override val incoming: Flow<SlideMessage> = emptyFlow()
    override suspend fun send(message: SlideMessage): EmptyResult<DataError.Network> = Result.Success(Unit)
}
