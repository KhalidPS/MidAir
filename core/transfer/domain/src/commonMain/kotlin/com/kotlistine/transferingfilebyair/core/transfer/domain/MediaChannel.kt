package com.kotlistine.transferingfilebyair.core.transfer.domain

import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result

/**
 * Reads files on the paired device while they are still only there: how a video plays on this phone from the frame
 * it is at on the other one, long before its file has come across.
 *
 * Two ways, best first: over the Wi-Fi network both phones are on (an address the sharing phone hands out, which a
 * video player reads by itself), or a range at a time over the pairing link ([open]).
 * Each side reads only what the other has [share]d, by an id they agreed on in a message.
 */
interface MediaChannel {
    /**
     * Whether the pairing link is fast enough to play a video from the peer as it arrives: on Wi-Fi (Direct, a
     * hotspot or a shared network), not on Bluetooth, which carries the link until it has been upgraded.
     */
    val isFast: Boolean

    /**
     * Lets the peer read the file at [uri] (a platform file reference) as [fileId], until [unshare]. Returns the
     * address where the peer can read it over the Wi-Fi network both are on, or null when this phone isn't on one.
     */
    fun share(fileId: String, uri: String): String?

    fun unshare(fileId: String)

    /** Whether the peer's address [url] (from its [share]) answers from here: both phones are on the same Wi-Fi. */
    suspend fun canReach(url: String): Boolean

    /** Opens the peer's file [fileId] from byte [offset] to its end, over the pairing link. Close it to stop early. */
    suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network>
}
