@file:OptIn(ExperimentalSerializationApi::class)

package com.kotlistine.transferingfilebyair.core.transfer.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString

/**
 * Wire form of a SlideMessage. Short serial names keep the ~60 Hz move stream small; changing one breaks
 * compatibility with installed peers, so add new fields with defaults instead.
 */
@Serializable
internal sealed interface SlideMessageDto {

    @Serializable
    @SerialName("start")
    data class DragStart(
        val itemId: String,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val cardWidthMm: Float,
        val cardHeightMm: Float,
        @ByteString val thumbnail: ByteArray? = null,
    ) : SlideMessageDto

    /** Sent every frame, so its field names are one or two letters on the wire. */
    @Serializable
    @SerialName("move")
    data class DragMove(
        @SerialName("i") val itemId: String,
        @SerialName("n") val seq: Int,
        @SerialName("e") val exitEdge: ExitEdgeDto,
        @SerialName("y") val alongEdgeMm: Float,
        @SerialName("p") val penetrationMm: Float,
        @SerialName("vp") val outwardMmPerS: Float,
        @SerialName("vy") val alongEdgeMmPerS: Float,
        @SerialName("t") val tiltDegrees: Float,
    ) : SlideMessageDto

    @Serializable
    @SerialName("cancel")
    data class DragCancel(val itemId: String) : SlideMessageDto

    @Serializable
    @SerialName("commit")
    data class DragCommit(val itemId: String, val outwardMmPerS: Float, val alongEdgeMmPerS: Float) : SlideMessageDto

    @Serializable
    @SerialName("file")
    data class FileOffer(val itemId: String, val transferId: Long) : SlideMessageDto

    @Serializable
    @SerialName("ping")
    data class Ping(val sentAtMillis: Long) : SlideMessageDto

    @Serializable
    @SerialName("pong")
    data class Pong(val pingSentAtMillis: Long, val answeredAtMillis: Long = 0L) : SlideMessageDto

    @Serializable
    @SerialName("swipe")
    data class SwipeCrossed(
        val leaving: Boolean,
        val edge: ExitEdgeDto,
        val alongEdgeMm: Float,
        val atMillis: Long,
        val acrossMmPerS: Float,
        val alongEdgeMmPerS: Float,
    ) : SlideMessageDto

    @Serializable
    @SerialName("play")
    data class PlaybackOffer(
        val itemId: String,
        val sizeBytes: Long,
        val positionMs: Long,
        val isPlaying: Boolean,
    ) : SlideMessageDto

    /** Sent a few times a second, so its field names are one letter on the wire. */
    @Serializable
    @SerialName("clock")
    data class PlaybackClock(
        @SerialName("i") val itemId: String,
        @SerialName("p") val positionMs: Long,
        @SerialName("r") val isPlaying: Boolean,
    ) : SlideMessageDto
}

@Serializable
internal enum class ExitEdgeDto { LEFT, RIGHT }
