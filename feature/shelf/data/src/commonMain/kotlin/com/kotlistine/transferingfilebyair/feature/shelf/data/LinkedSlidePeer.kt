package com.kotlistine.transferingfilebyair.feature.shelf.data

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkState
import com.kotlistine.transferingfilebyair.core.transfer.domain.PeerLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import com.kotlistine.transferingfilebyair.feature.shelf.data.file.ReceivedFileStore
import com.kotlistine.transferingfilebyair.feature.shelf.domain.IncomingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.OutgoingFile
import com.kotlistine.transferingfilebyair.feature.shelf.domain.ShelfRepository
import com.kotlistine.transferingfilebyair.feature.shelf.domain.SlidePeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** How long a let-go card waits to hear which incoming file is its own. */
private val OfferWait = 15.seconds

/**
 * The paired device as the shelf sees it, on a [PeerLink] (Nearby on Android). A handed-over card's file follows
 * it: the sender announces the transfer ([SlideMessage.FileOffer]) and streams the bytes; the receiver matches the
 * transfer to the card, waits for every byte, keeps the file and puts it on its shelf.
 */
class LinkedSlidePeer(
    private val peerLink: PeerLink,
    private val repository: ShelfRepository,
    private val store: ReceivedFileStore,
    /** How the screens sit, once lined up in pairing. */
    private val geometry: LinkGeometry,
    scope: CoroutineScope,
) : SlidePeer {

    override val transport: Transport get() = peerLink.transport

    /** The defaults (any edge leads to the peer, an 8 mm gap) until the screens are lined up. */
    override val link: SlideLink get() = geometry.link.value

    override val isLinked: Flow<Boolean> = peerLink.state.map { it is LinkState.Connected }.distinctUntilChanged()

    /** Transfer ids announced by the peer, by the item id on its shelf. */
    private val offers = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Items the user stopped before their transfer was known; their receive fails as soon as it is. */
    private val cancelled = MutableStateFlow<Set<String>>(emptySet())

    init {
        peerLink.transport.incoming
            .filterIsInstance<SlideMessage.FileOffer>()
            .onEach { offer -> offers.update { it + (offer.itemId to offer.transferId) } }
            .launchIn(scope)
    }

    override suspend fun send(file: OutgoingFile): EmptyResult<DataError> =
        peerLink.sendFile(file.uri) { transferId ->
            peerLink.transport.send(SlideMessage.FileOffer(file.itemId, transferId))
        }

    override suspend fun receive(file: IncomingFile, onProgress: (Float) -> Unit): Result<String, DataError> {
        val transferId = withTimeoutOrNull(OfferWait) {
            offers.map { it[file.peerItemId] }.filterNotNull().first()
        } ?: return Result.Failure(DataError.Network.DISCONNECTED)
        if (file.peerItemId in cancelled.value) {
            cancelled.update { it - file.peerItemId }
            offers.update { it - file.peerItemId }
            peerLink.cancelTransfer(transferId)
            return Result.Failure(DataError.Network.CANCELLED)
        }
        val location = try {
            peerLink.receiveFile(transferId, onProgress)
        } finally {
            offers.update { it - file.peerItemId }
        }
        val arrivedAt = when (val arrived = location) {
            is Result.Failure -> return arrived
            is Result.Success -> arrived.data
        }
        val kept = when (val stored = store.keep(arrivedAt, file.name, file.mimeType)) {
            is Result.Failure -> return stored
            is Result.Success -> stored.data
        }
        val added = repository.addFile(kept)
        if (added is Result.Failure) return added
        val item = repository.observeItems().first().firstOrNull { it.uri == kept }
            ?: return Result.Failure(DataError.Local.FILE_NOT_FOUND)
        return Result.Success(item.id)
    }

    override fun cancelReceive(peerItemId: String) {
        val transferId = offers.value[peerItemId]
        if (transferId != null) peerLink.cancelTransfer(transferId) else cancelled.update { it + peerItemId }
    }
}
