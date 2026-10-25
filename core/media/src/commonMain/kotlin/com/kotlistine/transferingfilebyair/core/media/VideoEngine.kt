package com.kotlistine.transferingfilebyair.core.media

import com.kotlistine.transferingfilebyair.core.domain.util.ByteStream
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays one video at a time. Its picture is drawn by [VideoSurface]s: several can show the same engine (a card in a
 * grid and its lifted copy), and the picture moves between them without the video stopping.
 *
 * Call it from the main thread only.
 */
interface VideoEngine {
    /** Whether the video plays (or will, once it has buffered). False while paused and when nothing is loaded. */
    val playWhenReady: StateFlow<Boolean>

    /** Things that happen during playback, for a single collector. */
    val events: Flow<VideoEngineEvent>

    /** Frames are moving right now: loaded, buffered enough, and not paused. */
    val isPlaying: Boolean

    /** Where the video is now, in milliseconds. Cheap enough to read every frame. */
    val positionMs: Long

    /** The video's length in milliseconds, or 0 while it isn't known yet. */
    val durationMs: Long

    /** Replaces whatever was loaded with [source], from [startAtMs], at full volume and normal speed. */
    fun load(source: VideoSource, startAtMs: Long = 0, playWhenReady: Boolean = true)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** 1 is normal speed. */
    fun setSpeed(speed: Float)

    /** From 0 (muted) to 1. */
    fun setVolume(volume: Float)

    /** Stops and frees the decoder. A later [load] starts again. */
    fun release()
}

sealed interface VideoEngineEvent {
    /** Played to the end. */
    data object Ended : VideoEngineEvent

    /** The video can't be played; nothing more happens until the next load. */
    data class Failed(val error: DataError.Local) : VideoEngineEvent
}

sealed interface VideoSource {
    /** A file on this device: a `content://` URI on Android. */
    data class Local(val uri: String) : VideoSource

    /**
     * A file still on another device, read a range at a time as it plays: [open] gives its bytes from an offset to
     * the end. Not a data class: [open] is compared by identity.
     */
    class Remote(
        val id: String,
        val sizeBytes: Long,
        val open: suspend (offset: Long) -> Result<ByteStream, DataError.Network>,
    ) : VideoSource
}
