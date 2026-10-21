package com.kotlistine.transferingfilebyair.feature.shelf.domain

import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transport

/** The device on the other side of the gap: where slid cards go, and where incoming ones come from. */
interface SlidePeer {
    val transport: Transport

    /** How the two screens sit next to each other. */
    val link: SlideLink

    /** Takes in a file the peer slid over and puts it on this shelf. Returns the new shelf item's id. */
    suspend fun receive(file: IncomingFile): Result<String, DataError>
}

/** A file the peer is handing over, as described when its card was lifted. */
data class IncomingFile(
    /** The item's id on the peer's shelf. */
    val peerItemId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)
