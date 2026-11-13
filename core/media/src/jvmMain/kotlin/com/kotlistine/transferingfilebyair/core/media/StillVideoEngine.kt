package com.kotlistine.transferingfilebyair.core.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The desktop has no video player yet: a video card shows its still picture and never plays. Loading one is
 * accepted and ignored, so a video sent from the phone still arrives as a file.
 */
internal class StillVideoEngine : VideoEngine {
    private val wantsToPlay = MutableStateFlow(false)
    override val playWhenReady: StateFlow<Boolean> = wantsToPlay.asStateFlow()
    override val events: Flow<VideoEngineEvent> = emptyFlow()
    override val isPlaying: Boolean = false
    override val positionMs: Long = 0
    override val durationMs: Long = 0

    override fun load(source: VideoSource, startAtMs: Long, playWhenReady: Boolean) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun release() = Unit
}
