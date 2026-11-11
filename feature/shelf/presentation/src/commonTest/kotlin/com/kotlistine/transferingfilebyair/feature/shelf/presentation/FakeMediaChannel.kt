package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeMediaChannel(
    isFast: Boolean = true,
    /** The Wi-Fi address a share hands out; null: this phone isn't on a Wi-Fi network. */
    var wifiUrl: ((fileId: String) -> String)? = null,
    /** Whether a peer's Wi-Fi address answers from here. */
    var reachable: Boolean = true,
) : MediaChannel {
    private val fast = MutableStateFlow(isFast)

    /** Setting it tells whoever watches the link's speed, as the real link does once it is on Wi-Fi Direct. */
    override var isFast: Boolean
        get() = fast.value
        set(value) {
            fast.value = value
        }

    override val isFastUpdates: Flow<Boolean> = fast

    /** Shared files by id. */
    val shared = mutableMapOf<String, String>()

    /** Every hold asked for (true: a card is in the air). */
    val holds = mutableListOf<Boolean>()

    override fun holdFetches(hold: Boolean) {
        holds += hold
    }

    /** Every range asked for: file id and offset. */
    val opened = mutableListOf<Pair<String, Long>>()

    override fun share(fileId: String, uri: String): String? {
        shared[fileId] = uri
        return wifiUrl?.invoke(fileId)
    }

    override fun unshare(fileId: String) {
        shared.remove(fileId)
    }

    override suspend fun canReach(url: String): Boolean = reachable

    override suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network> {
        opened += fileId to offset
        return Result.Failure(DataError.Network.DISCONNECTED)
    }

    /** Every file let go of, in order. */
    val forgotten = mutableListOf<String>()

    override fun forget(fileId: String) {
        forgotten += fileId
    }
}
