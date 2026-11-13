package com.kotlistine.transferingfilebyair.core.media

/**
 * Where playback is, in microseconds of the video: the one clock the picture and the sound both follow. It runs on the
 * computer's monotonic clock, at [speed], from wherever it was last moved to; stopped, it stays put.
 *
 * Read from any thread.
 */
internal class PlaybackClock(private val nanoTime: () -> Long = System::nanoTime) {
    private var baseUs = 0L
    private var baseNanos = 0L
    private var currentSpeed = 1f
    private var running = false

    @Synchronized
    fun nowUs(): Long = if (!running) baseUs else baseUs + ((nanoTime() - baseNanos) / 1000.0 * currentSpeed).toLong()

    val isRunning: Boolean
        @Synchronized get() = running

    val speed: Float
        @Synchronized get() = currentSpeed

    @Synchronized
    fun start() {
        if (running) return
        baseNanos = nanoTime()
        running = true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        baseUs = nowUs()
        running = false
    }

    /** Goes on from where it is now at [speed] (1 is normal). */
    @Synchronized
    fun setSpeed(speed: Float) {
        baseUs = nowUs()
        baseNanos = nanoTime()
        currentSpeed = speed
    }

    /** Jumps to [us], running or not as before. */
    @Synchronized
    fun moveTo(us: Long) {
        baseUs = us
        baseNanos = nanoTime()
    }
}
