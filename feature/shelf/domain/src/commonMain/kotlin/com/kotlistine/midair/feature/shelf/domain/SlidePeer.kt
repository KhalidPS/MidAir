package com.kotlistine.midair.feature.shelf.domain

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.core.domain.util.EmptyResult
import com.kotlistine.midair.core.domain.util.Result
import com.kotlistine.midair.core.transfer.domain.MediaChannel
import com.kotlistine.midair.core.transfer.domain.SlideLink
import com.kotlistine.midair.core.transfer.domain.TransferProgress
import com.kotlistine.midair.core.transfer.domain.Transport
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

    /**
     * Sends the file behind a handed-over card; returns once the peer has all of it. Files go one at a time, so each
     * gets the link's full speed and the cards' own messages aren't held up behind several files: a file that has to
     * wait its turn reports [TransferProgress.queued] (and the peer hears it is coming). Fails with
     * [DataError.Network.CANCELLED] once either side stopped it ([cancelSend], or the peer's [cancelReceive]).
     */
    suspend fun send(file: OutgoingFile, progress: TransferProgress = TransferProgress.None): EmptyResult<DataError>

    /** Stops sending the file of this device's item [itemId], queued or under way; its send fails as cancelled. */
    fun cancelSend(itemId: String)

    /**
     * Takes in a file the peer slid over and puts it on this shelf, reporting to [progress] as it arrives. Waits as
     * long as the peer says it is queued. Returns the new shelf item's id.
     */
    suspend fun receive(file: IncomingFile, progress: TransferProgress = TransferProgress.None): Result<String, DataError>

    /** Stops receiving the file of the peer's item [peerItemId] (the peer is told); its receive fails as cancelled. */
    fun cancelReceive(peerItemId: String)
}

/** A file this device is handing over: the shelf item and the reference to its bytes. */
data class OutgoingFile(
    val itemId: String,
    val uri: String,
    /**
     * Its card carried its playing video to the peer, which may be reading the file already: the peer says whether
     * it takes the file from what it reads, or wants it sent.
     */
    val peerMayFetch: Boolean = false,
)

/** A file the peer is handing over, as described when its card was lifted. */
data class IncomingFile(
    /** The item's id on the peer's shelf. */
    val peerItemId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val delivery: Delivery = Delivery.SENT,
)

/** How a handed-over file gets here. */
enum class Delivery {
    /** The peer sends it right after its card, as any file. */
    SENT,

    /** The peer offered its card's video and waits to hear how the file should go: it is asked to send it. */
    ASK_TO_SEND,

    /**
     * This device plays the card's video from the peer's file over the link, so most of the file is here already: it
     * takes the rest the same way, instead of a second transfer competing with the video. Sent if that fails.
     */
    FETCH,

    /**
     * This device pulls the file from the peer in pieces over the pairing link, one file at a time, and asks for no
     * new piece while a card is in the air ([MediaChannel.holdFetches]): the card's moves never wait behind a whole
     * file. Sent as usual if that fails.
     */
    PULL,
}
