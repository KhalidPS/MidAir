package com.kotlistine.midair.feature.shelf.presentation

import com.kotlistine.midair.core.media.VideoEngine
import com.kotlistine.midair.core.media.VideoEngineEvent
import com.kotlistine.midair.core.media.VideoSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeVideoEngine : VideoEngine {
    override val playWhenReady = MutableStateFlow(false)

    /** Send to this to make something happen during playback. */
    val playbackEvents = Channel<VideoEngineEvent>(Channel.UNLIMITED)
    override val events: Flow<VideoEngineEvent> = playbackEvents.receiveAsFlow()

    /** Whether frames are moving; set it to say the video has buffered. */
    override var isPlaying = false
    override var positionMs = 0L
    override var durationMs = 0L

    /** What is loaded now; null before the first load and after a release. */
    var loaded: VideoSource? = null
    var loadedAtMs = 0L
    var releaseCount = 0
    var currentVolume = 1f
    var currentSpeed = 1f
    val seeks = mutableListOf<Long>()

    override fun load(source: VideoSource, startAtMs: Long, playWhenReady: Boolean) {
        loaded = source
        loadedAtMs = startAtMs
        positionMs = startAtMs
        currentVolume = 1f
        currentSpeed = 1f
        this.playWhenReady.value = playWhenReady
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun setSpeed(speed: Float) {
        currentSpeed = speed
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume
    }

    override fun play() {
        playWhenReady.value = true
    }

    override fun pause() {
        playWhenReady.value = false
    }

    override fun release() {
        loaded = null
        releaseCount++
        playWhenReady.value = false
    }
}
