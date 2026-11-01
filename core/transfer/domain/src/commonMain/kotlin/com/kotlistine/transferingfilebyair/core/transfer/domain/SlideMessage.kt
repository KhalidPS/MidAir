package com.kotlistine.transferingfilebyair.core.transfer.domain

/**
 * What two devices say to each other while a card slides from one to the other. Geometry is in millimetres and
 * relative to the exit edge, so neither side needs the other's pixel density or orientation.
 */
sealed interface SlideMessage {

    /**
     * Sent the moment a card is lifted, long before it reaches the edge, so the peer already has the artwork when
     * the card arrives (no pop-in).
     */
    data class DragStart(
        val itemId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val cardWidthMm: Float,
        val cardHeightMm: Float,
        /** The card as the sender draws it, small and compressed. Null if it couldn't be captured. */
        val thumbnail: ByteArray?,
    ) : SlideMessage {
        override fun equals(other: Any?): Boolean =
            other is DragStart &&
                itemId == other.itemId &&
                name == other.name &&
                mimeType == other.mimeType &&
                sizeBytes == other.sizeBytes &&
                cardWidthMm == other.cardWidthMm &&
                cardHeightMm == other.cardHeightMm &&
                thumbnail.contentEquals(other.thumbnail)

        override fun hashCode(): Int {
            var result = itemId.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + sizeBytes.hashCode()
            result = 31 * result + cardWidthMm.hashCode()
            result = 31 * result + cardHeightMm.hashCode()
            result = 31 * result + (thumbnail?.contentHashCode() ?: 0)
            return result
        }
    }

    /** Where the card is, streamed every frame while it is near the edge (and while it flies out). */
    data class DragMove(
        val itemId: String,
        /** Increases with every move of one drag, so the peer can drop moves that arrive out of order. */
        val seq: Int,
        val exitEdge: ExitEdge,
        /** Card top, measured down from the top of the sender's screen. */
        val alongEdgeMm: Float,
        /** How far the card's leading edge is past the exit edge; negative while it is still inside. */
        val penetrationMm: Float,
        /** Speed toward the exit edge (negative = back inward). */
        val outwardMmPerS: Float,
        /** Speed along the edge, downward positive. */
        val alongEdgeMmPerS: Float,
        val tiltDegrees: Float,
        /**
         * When the card was here, by the sender's clock (epoch milliseconds), so the peer can tell how unevenly moves
         * arrive and play them back at an even pace. 0 when unknown (sent by an older build).
         */
        val atMillis: Long = 0L,
    ) : SlideMessage

    /** The card was let go short of the edge and sprang back: the peer withdraws whatever it showed. */
    data class DragCancel(val itemId: String) : SlideMessage

    /** The card left the sender's screen for good: the peer lands it and takes the file. */
    data class DragCommit(
        val itemId: String,
        val outwardMmPerS: Float,
        val alongEdgeMmPerS: Float,
    ) : SlideMessage

    /**
     * The file behind a handed-over card is on its way as transfer [transferId] (sent just before the bytes), so the
     * receiver knows which incoming file is which card.
     */
    data class FileOffer(val itemId: String, val transferId: Long) : SlideMessage

    /** Round-trip time probe; answered with [Pong] carrying the same timestamp. */
    data class Ping(val sentAtMillis: Long) : SlideMessage

    /** [answeredAtMillis] is the answering device's clock when it answered (0 if unknown), to compare the clocks. */
    data class Pong(val pingSentAtMillis: Long, val answeredAtMillis: Long = 0L) : SlideMessage

    /** A line-up swipe crossed the sender's screen edge; with the receiver's own crossing, it lines the screens up. */
    data class SwipeCrossed(val crossing: EdgeCrossing) : SlideMessage

    /**
     * The lifted card (sent just before) is a video playing on the sender, readable over the media channel as
     * [itemId]. The receiver starts it at the same frame, muted, so both halves of the card show one video.
     */
    data class PlaybackOffer(
        val itemId: String,
        val sizeBytes: Long,
        /** Where the sender's video was when this left. */
        val positionMs: Long,
        val isPlaying: Boolean,
        /** Where it can be read over the Wi-Fi network, if the sender is on one (the receiver checks it can reach it). */
        val httpUrl: String? = null,
    ) : SlideMessage

    /** Where the sender's video is, reported a few times a second while its card is in the air. */
    data class PlaybackClock(val itemId: String, val positionMs: Long, val isPlaying: Boolean) : SlideMessage

    /**
     * The receiver's answer about the file behind a card whose video it was offered ([PlaybackOffer]): whether the
     * sender should send it, or the receiver is taking it from the video's pieces it reads anyway, and how that went.
     * The sender waits for it after the commit instead of sending the file at once.
     */
    data class FileRoute(val itemId: String, val route: Route) : SlideMessage {
        enum class Route {
            /** Send the file as usual (also after a fetch that failed while the link stayed up). */
            SEND,

            /** The receiver is fetching it; [FETCHED], [SEND] or [CANCELLED] follows. */
            FETCHING,

            /** The receiver has all of it, checked against the sender's, and on its shelf: the send is done. */
            FETCHED,

            /** The receiver's user stopped it. */
            CANCELLED,
        }
    }
}
