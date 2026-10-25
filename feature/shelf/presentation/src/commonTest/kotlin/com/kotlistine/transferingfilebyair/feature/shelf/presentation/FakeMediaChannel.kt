package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel

class FakeMediaChannel(override var isFast: Boolean = true) : MediaChannel {
    /** Shared files by id. */
    val shared = mutableMapOf<String, String>()

    /** Every range asked for: file id and offset. */
    val opened = mutableListOf<Pair<String, Long>>()

    override fun share(fileId: String, uri: String) {
        shared[fileId] = uri
    }

    override fun unshare(fileId: String) {
        shared.remove(fileId)
    }

    override suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network> {
        opened += fileId to offset
        return Result.Failure(DataError.Network.DISCONNECTED)
    }
}
