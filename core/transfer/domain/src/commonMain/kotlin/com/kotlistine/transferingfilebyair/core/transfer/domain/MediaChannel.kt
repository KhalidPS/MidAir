package com.kotlistine.transferingfilebyair.core.transfer.domain

import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /** [isFast] as it changes (the link starts on Bluetooth and moves to Wi-Fi a few seconds later). */
    val isFastUpdates: Flow<Boolean> get() = flowOf(isFast)

    /**
     * Lets the peer read the file at [uri] (a platform file reference) as [fileId], until [unshare]. Returns the
     * address where the peer can read it over the Wi-Fi network both are on, or null when this phone isn't on one.
     */
    fun share(fileId: String, uri: String): String?

    fun unshare(fileId: String)

    /** Whether the peer's address [url] (from its [share]) answers from here: both phones are on the same Wi-Fi. */
    suspend fun canReach(url: String): Boolean

    /**
     * Opens the peer's file [fileId] from byte [offset] to its end, over the pairing link. Close it to stop early.
     * What it reads is kept, for [fetch]. Once the file is on this phone ([fetch] or [settle]), it reads from there.
     */
    suspend fun open(fileId: String, offset: Long): Result<ByteStream, DataError.Network>

    /**
     * Takes in all of the peer's file [fileId] ([sizeBytes] long) over the pairing link: what [open] already read,
     * and the rest, asked for only while nothing [open]ed is waiting for bytes. Checks it is the peer's file byte for
     * byte, and returns where it is (a platform file reference, to keep with [settle] or drop with [forget]).
     * Reports how much is here (0..1) to [onProgress].
     */
    suspend fun fetch(
        fileId: String,
        sizeBytes: Long,
        onProgress: (Float) -> Unit = {},
    ): Result<String, DataError> = Result.Failure(DataError.Network.UNKNOWN)

    /**
     * The peer's file [fileId] is on this phone for good, at [uri] (a platform file reference): [open] reads it from
     * there from now on, also for a stream already open, and what was fetched or kept of it is let go.
     */
    fun settle(fileId: String, uri: String) = Unit

    /**
     * Reads ahead the parts of the peer's file [fileId] ([sizeBytes] long) a video player needs before it can show a
     * frame (its header and its index), over the pairing link, so [open] finds them here when its card comes. Kept
     * until [forget]. Never in the way of a card in the air or of a video playing now.
     */
    fun prefetch(fileId: String, sizeBytes: Long) = Unit

    /** Lets go of everything kept of the peer's file [fileId]: it is no longer wanted, or is about to be read anew. */
    fun forget(fileId: String) = Unit

    /**
     * Whether a card is in the air between the two phones. While one is, [fetch] asks for no new pieces: every byte
     * shares the one connection with the card's moves, and a file's bytes going at full speed held those moves up for
     * seconds. Pieces already on their way still come.
     */
    fun holdFetches(hold: Boolean) = Unit

    /** How many bytes of this phone's [share]d file [fileId] the peer has taken so far, in pieces that went whole. */
    fun servedBytes(fileId: String): Long = 0L
}
