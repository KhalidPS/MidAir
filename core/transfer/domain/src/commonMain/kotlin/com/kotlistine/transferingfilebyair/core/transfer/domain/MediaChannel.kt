package com.kotlistine.transferingfilebyair.core.transfer.domain

import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result

/**
 * Reads files on the paired device a range at a time, while they are still only there: how a video plays on this
 * phone from the frame it is at on the other one, long before its file has come across.
 *
 * Each side reads only what the other has [share]d, by an id they agreed on in a message.
 */
interface MediaChannel {
    /**
     * Whether the link is fast enough to play a video from the peer as it arrives: on Wi-Fi (Direct, a hotspot or a
     * shared network), not on Bluetooth, which carries the link until it has been upgraded.
     */
    val isFast: Boolean

    /** Lets the peer read the file at [uri] (a platform file reference) as [fileId], until [unshare]. */
    fun share(fileId: String, uri: String)

    fun unshare(fileId: String)

    /** Opens the peer's file [fileId] from byte [offset] to its end. Close the stream to stop it early. */
    suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network>
}
