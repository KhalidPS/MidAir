package com.kotlistine.transferingfilebyair.feature.shelf.domain

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.EmptyResult
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.MediaChannel
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport
import kotlinx.coroutines.flow.Flow

/** The device on the other side of the gap: where slid cards go, and where incoming ones come from. */
interface SlidePeer {
    val transport: Transport

    /** How the two screens sit next to each other. */
    val link: SlideLink

    /** Whether a device is paired right now; slides are mirrored only then. */
    val isLinked: Flow<Boolean>

    /** Plays the peer's videos from the frame they are at, while their files are still on the peer (null: it can't). */
    val media: MediaChannel? get() = null

    /** Sends the file behind a handed-over card; returns once the peer has all of it. */
    suspend fun send(file: OutgoingFile): EmptyResult<DataError>

    /**
     * Takes in a file the peer slid over and puts it on this shelf, reporting how much has arrived (0..1) to
     * [onProgress]. Returns the new shelf item's id.
     */
    suspend fun receive(file: IncomingFile, onProgress: (Float) -> Unit = {}): Result<String, DataError>

    /** Stops receiving the file of the peer's item [peerItemId]; its receive then fails as cancelled. */
    fun cancelReceive(peerItemId: String)
}

/** A file this device is handing over: the shelf item and the reference to its bytes. */
data class OutgoingFile(val itemId: String, val uri: String)

/** A file the peer is handing over, as described when its card was lifted. */
data class IncomingFile(
    /** The item's id on the peer's shelf. */
    val peerItemId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)
