package com.kotlistine.transferingfilebyair.core.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

private const val Tag = "AirVideo"

/**
 * [VideoEngine] on Media3 ExoPlayer. The player is built on the first [load] and freed by [release], so an engine
 * that never plays costs nothing.
 *
 * The picture goes to one [VideoOutput] at a time: the last activated of the active ones. Moving it to another output
 * keeps the same decoder running (nothing is prepared again), so the video never stops. The new output shows the old
 * one's current frame until its own first frame arrives, and the old output keeps the video until the new one can
 * take it.
 */
internal class ExoVideoEngine(private val context: Context) : VideoEngine {

    private var player: ExoPlayer? = null

    private val _playWhenReady = MutableStateFlow(false)
    override val playWhenReady: StateFlow<Boolean> = _playWhenReady

    private val eventChannel = Channel<VideoEngineEvent>(Channel.BUFFERED)
    override val events: Flow<VideoEngineEvent> = eventChannel.receiveAsFlow()

    override val isPlaying: Boolean get() = player?.isPlaying == true
    override val positionMs: Long get() = player?.currentPosition ?: 0L
    override val durationMs: Long get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L

    /** Width / height of the picture as shown (after rotation), or 0 until it is known. */
    var aspectRatio by mutableFloatStateOf(0f)
        private set

    /** Active outputs in the order they were activated. */
    private val active = mutableListOf<VideoOutput>()

    /** The output the player draws into. */
    private var shown: VideoOutput? = null

    /** The output that should show the video, as of the last [route]. */
    private var wanted: VideoOutput? = null

    /** Bumped by every load: outputs made before it showed another video and never take this one. */
    private var generation = 0

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Also when the player pauses itself: audio focus lost, headphones unplugged.
            _playWhenReady.value = playWhenReady
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val name = when (playbackState) {
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "idle"
            }
            Log.i(Tag, "State $name at ${player?.currentPosition} ms")
            if (playbackState == Player.STATE_ENDED) eventChannel.trySend(VideoEngineEvent.Ended)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(Tag, "Failed: ${error.errorCodeName}", error)
            eventChannel.trySend(VideoEngineEvent.Failed(playbackErrorToDataError(error.errorCode)))
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            aspectRatio = if (videoSize.height > 0) {
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            } else {
                0f
            }
        }
    }

    override fun load(source: VideoSource, startAtMs: Long, playWhenReady: Boolean) {
        val player = player ?: newPlayer()
        generation++
        active.clear()
        shown = null
        wanted = null
        player.clearVideoSurface()
        aspectRatio = 0f
        player.volume = 1f
        player.playbackParameters = PlaybackParameters.DEFAULT
        when (source) {
            is VideoSource.Local -> player.setMediaItem(MediaItem.fromUri(source.uri), startAtMs)
            is VideoSource.Remote -> player.setMediaSource(remoteMediaSource(source), startAtMs)
        }
        Log.i(Tag, "Load ${if (source is VideoSource.Remote) "from the other phone" else "local"} at $startAtMs ms")
        player.playWhenReady = playWhenReady
        player.prepare()
        _playWhenReady.value = playWhenReady
    }

    @OptIn(UnstableApi::class)
    private fun remoteMediaSource(source: VideoSource.Remote) =
        ProgressiveMediaSource.Factory(DataSource.Factory { RemoteDataSource(source) })
            .createMediaSource(MediaItem.fromUri("peer://video/${source.id}"))

    override fun play() {
        val player = player ?: return
        if (!player.playWhenReady) Log.i(Tag, "Play at ${player.currentPosition} ms")
        player.play()
    }

    override fun pause() {
        val player = player ?: return
        if (player.playWhenReady) Log.i(Tag, "Pause at ${player.currentPosition} ms")
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        val player = player ?: return
        Log.i(Tag, "Seek from ${player.currentPosition} to $positionMs ms")
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun setSpeed(speed: Float) {
        val player = player ?: return
        if (player.playbackParameters.speed == speed) return
        Log.i(Tag, "Speed $speed at ${player.currentPosition} ms")
        player.setPlaybackSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    override fun release() {
        val player = player ?: return
        this.player = null
        player.removeListener(listener)
        player.release()
        shown = null
        _playWhenReady.value = false
    }

    // region Outputs, driven by VideoSurface.

    /** An output for a surface being composed now. Made while the video shows elsewhere, it starts with that frame. */
    fun newOutput(): VideoOutput = VideoOutput(generation).apply { cover = shown?.picture() }

    fun activate(output: VideoOutput) {
        if (output.generation != generation) return
        active.remove(output)
        active += output
        route()
    }

    fun deactivate(output: VideoOutput) {
        if (active.remove(output)) route()
    }

    fun onSurfaceAvailable(output: VideoOutput, texture: SurfaceTexture) {
        output.surface = Surface(texture)
        route()
    }

    /** The player is moved off the surface before it is released. */
    fun onSurfaceDestroyed(output: VideoOutput) {
        val surface = output.surface ?: return
        output.surface = null
        output.hasFrame = false
        route()
        surface.release()
    }

    fun onFrame(output: VideoOutput) {
        if (output !== shown) return
        output.hasFrame = true
        output.cover = null
    }

    /** Points the player at the output that should show the video, as soon as that output can take it. */
    private fun route() {
        val target = active.lastOrNull()
        if (target !== wanted) {
            wanted = target
            // It shows an old frame of its own, or one from when it was made (it may have waited, inactive, since):
            // until its first new frame, it shows the current one.
            if (target != null && target !== shown) {
                shown?.picture()?.let { target.cover = it }
                target.hasFrame = false
            }
        }
        val player = player ?: return
        val next = when {
            target?.surface != null -> target
            // Make before break: the video stays where it is until the new output is ready.
            shown?.surface != null -> shown
            else -> active.lastOrNull { it.surface != null }
        }
        if (next === shown) return
        shown = next
        // Returns once the old surface is no longer drawn into.
        if (next == null) player.clearVideoSurface() else player.setVideoSurface(next.surface)
    }

    /** What the output shows right now: its own frame, or the one covering it. */
    private fun VideoOutput.picture(): ImageBitmap? =
        if (hasFrame) view?.takeIf { it.isAvailable }?.bitmap?.asImageBitmap() else cover

    // endregion

    @OptIn(UnstableApi::class)
    private fun newPlayer(): ExoPlayer {
        // A video streamed from the other phone shares the link with the card's moves: it starts after under a second
        // of video and keeps 5-10 s ahead, instead of 2.5 s and 50 s. A local file doesn't notice.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 5_000,
                /* maxBufferMs = */ 10_000,
                /* bufferForPlaybackMs = */ 750,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(context)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build()
            .also {
                it.addListener(listener)
                player = it
            }
    }
}

/** One [VideoSurface]'s TextureView, as the engine sees it. */
internal class VideoOutput(val generation: Int) {
    var view: TextureView? = null
    var surface: Surface? = null

    /** The view shows a frame of the video. Until then the [cover] (or the surface's placeholder) is drawn over it. */
    var hasFrame by mutableStateOf(false)
    var cover: ImageBitmap? by mutableStateOf(null)
}

internal fun playbackErrorToDataError(errorCode: Int): DataError.Local = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> DataError.Local.FILE_NOT_FOUND
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> DataError.Local.PERMISSION_DENIED
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> DataError.Local.UNSUPPORTED_FORMAT
    else -> DataError.Local.UNKNOWN
}
