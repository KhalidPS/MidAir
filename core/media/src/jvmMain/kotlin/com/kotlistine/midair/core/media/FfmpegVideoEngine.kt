package com.kotlistine.midair.core.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kotlistine.midair.core.domain.util.DataError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.max
import kotlin.math.roundToInt

/** Frames are scaled down to fit this (pixels, longest side): a card never shows more, and smaller frames copy faster. */
private const val MaxFrameSide = 960

/** Decoded ahead of the clock, at most: enough to ride out a slow frame, little enough to start a seek quickly. */
private const val MaxQueuedFrames = 4

/** A frame later than this behind the clock is skipped without being drawn. */
private const val LateFrameUs = 60_000L

/** Sound is written this far ahead at most (the line's buffer), and dropped when heard later than [LateSoundUs]. */
private const val SoundBufferMillis = 300
private const val LateSoundUs = 60_000L
private const val EarlySoundUs = 10_000L

/** How often the engine looks at the clock when no surface is drawing it (to start, and to notice the end). */
private const val IdleTickMillis = 30L

/**
 * Plays videos with FFmpeg (through JavaCV), on the desktop. Two readers go through the file side by side: one decodes
 * the picture into a few frames ahead (scaled down, as Skia images), the other the sound into the speakers. Both follow
 * one [PlaybackClock]; the clock starts once the first frame is decoded, so the picture never trails the sound.
 *
 * [VideoSurface] draws [frame] and calls [tick] on every frame it draws, which moves to the frame due by then.
 *
 * Called on [main] only (the UI thread); the readers run on IO threads.
 */
internal class FfmpegVideoEngine(private val main: CoroutineDispatcher = Dispatchers.Main) : VideoEngine {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val clock = PlaybackClock()
    private val wantsToPlay = MutableStateFlow(false)
    private val eventChannel = Channel<VideoEngineEvent>(Channel.BUFFERED)
    private var playback: Playback? = null

    override val playWhenReady: StateFlow<Boolean> = wantsToPlay.asStateFlow()
    override val events: Flow<VideoEngineEvent> = eventChannel.receiveAsFlow()

    /** The picture to draw now; replaced (and the one before closed) on the UI thread only. */
    internal var frame: VideoFrame? by mutableStateOf(null)
        private set

    /** How far the picture is turned, clockwise, to stand the right way up (a phone's portrait video: 90). */
    internal var rotationDegrees: Int by mutableStateOf(0)
        private set

    /** True while frames are due: surfaces then call [tick] on every frame they draw. */
    internal var isAdvancing: Boolean by mutableStateOf(false)
        private set

    override val isPlaying: Boolean
        get() = playback?.let { it.isReady && !it.hasEnded && clock.isRunning } == true

    override val positionMs: Long
        get() {
            if (playback == null) return 0L
            val nowUs = clock.nowUs().coerceAtLeast(0L)
            return (if (durationMs > 0) nowUs.coerceAtMost(durationMs * 1000) else nowUs) / 1000
        }

    override var durationMs: Long = 0L
        private set

    override fun load(source: VideoSource, startAtMs: Long, playWhenReady: Boolean) {
        release()
        val input = when (source) {
            is VideoSource.Local -> GrabberInput.Path(localPath(source.uri))
            is VideoSource.Url -> GrabberInput.Path(source.url)
            // On the other device, read a piece at a time over the link: a card's video arriving.
            is VideoSource.Remote -> GrabberInput.Remote(source)
        }
        if (input is GrabberInput.Path && source is VideoSource.Local && !File(input.path).exists()) {
            eventChannel.trySend(VideoEngineEvent.Failed(DataError.Local.FILE_NOT_FOUND))
            return
        }
        val startUs = startAtMs.coerceAtLeast(0) * 1000
        val started = Playback(input, startUs)
        playback = started
        wantsToPlay.value = playWhenReady
        clock.stop()
        clock.setSpeed(1f)
        clock.moveTo(startUs)
        isAdvancing = true
        started.jobs += scope.launch(Dispatchers.IO) { readPicture(started) }
        started.jobs += scope.launch {
            while (isActive) {
                tick()
                delay(IdleTickMillis)
            }
        }
    }

    override fun play() {
        wantsToPlay.value = true
        val current = playback ?: return
        if (current.isReady && !current.hasEnded) clock.start()
        isAdvancing = current.isAdvancing()
    }

    override fun pause() {
        wantsToPlay.value = false
        clock.stop()
        isAdvancing = playback?.isAdvancing() == true
    }

    override fun seekTo(positionMs: Long) {
        val current = playback ?: return
        val targetUs = positionMs.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE / 1000) * 1000
        clock.stop()
        clock.moveTo(targetUs)
        // Waits for the first frame from there, as at the start.
        current.isReady = false
        current.hasEnded = false
        current.endReported = false
        current.seek(targetUs)
        isAdvancing = true
    }

    override fun setSpeed(speed: Float) {
        clock.setSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        val current = playback ?: return
        current.volume = volume.coerceIn(0f, 1f)
        startSound(current)
    }

    /**
     * Starts the sound's reader once the video is known to have sound and is to be heard: a card's video arriving
     * plays muted, and a second reader of it would only take bytes from the link for nothing.
     */
    private fun startSound(current: Playback) {
        if (!current.hasSound || current.volume <= 0f || current.isSoundStarted) return
        current.isSoundStarted = true
        current.jobs += scope.launch(Dispatchers.IO) { playSound(current) }
    }

    override fun release() {
        val current = playback ?: return
        playback = null
        current.jobs.forEach(Job::cancel)
        current.clearFrames()
        clock.stop()
        frame?.image?.close()
        frame = null
        isAdvancing = false
        durationMs = 0L
        rotationDegrees = 0
    }

    /** Moves the picture to the frame due by now; starts the clock once the first one is in. On the UI thread. */
    internal fun tick() {
        val current = playback ?: return
        if (!current.isReady) {
            val first = current.pollFrame() ?: return
            show(first)
            current.isReady = true
            current.lastShownUs = first.timestampUs
            if (wantsToPlay.value) clock.start()
        }
        val now = clock.nowUs()
        var due: VideoFrame? = null
        while (true) {
            val next = current.peekFrame() ?: break
            if (next.timestampUs > now) break
            current.pollFrame()
            due?.image?.close()
            due = next
        }
        due?.let {
            show(it)
            current.lastShownUs = it.timestampUs
        }
        if (!current.hasEnded && current.pictureEnded && current.peekFrame() == null && now >= current.lastShownUs) {
            current.hasEnded = true
            clock.stop()
            if (!current.endReported) {
                current.endReported = true
                eventChannel.trySend(VideoEngineEvent.Ended)
            }
        }
        isAdvancing = current.isAdvancing()
    }

    private fun Playback.isAdvancing(): Boolean = !isReady || (wantsToPlay.value && !hasEnded)

    private fun show(next: VideoFrame) {
        val before = frame
        frame = next
        if (before !== next) before?.image?.close()
    }

    /** The picture's reader: opens the file (and starts the sound's), then decodes frames ahead of the clock. */
    private suspend fun readPicture(playback: Playback) {
        val grabber = try {
            open(playback.input, forPicture = true)
        } catch (e: Exception) {
            fail(playback, ffmpegErrorToDataError(e.message))
            return
        }
        try {
            if (!grabber.hasVideo()) {
                fail(playback, DataError.Local.UNSUPPORTED_FORMAT)
                return
            }
            val width = grabber.imageWidth
            val height = grabber.imageHeight
            val scale = minOf(1.0, MaxFrameSide.toDouble() / max(width, height).coerceAtLeast(1))
            grabber.imageWidth = (width * scale).roundToInt().coerceAtLeast(2)
            grabber.imageHeight = (height * scale).roundToInt().coerceAtLeast(2)
            val rotation = clockwiseRotation(grabber.displayRotation)
            val length = grabber.lengthInTime / 1000
            val hasSound = grabber.hasAudio()
            withContext(main) {
                if (this@FfmpegVideoEngine.playback !== playback) return@withContext
                durationMs = length
                rotationDegrees = rotation
                playback.hasSound = hasSound
                startSound(playback)
            }
            if (playback.startUs > 0) grabber.timestamp = playback.startUs
            while (currentCoroutineContext().isActive) {
                playback.takePictureSeek()?.let { seek ->
                    grabber.timestamp = seek.targetUs
                    playback.pictureEnded = false
                    playback.generation = seek.generation
                }
                if (playback.queuedFrames() >= MaxQueuedFrames) {
                    delay(4)
                    continue
                }
                val decoded = grabber.grabImage()
                if (decoded == null) {
                    playback.pictureEnded = true
                    delay(IdleTickMillis)
                    continue
                }
                val timestamp = decoded.timestamp
                // Behind the clock already (a slow stretch): skipped, so the picture catches up instead of lagging.
                if (playback.isReady && clock.isRunning && timestamp < clock.nowUs() - LateFrameUs) continue
                val image = decoded.toSkiaImage() ?: continue
                playback.offer(VideoFrame(timestamp, image, playback.generation))
            }
        } catch (e: Exception) {
            if (this.playback === playback) fail(playback, ffmpegErrorToDataError(e.message))
        } catch (_: LinkageError) {
            // FFmpeg's or Skia's native code is missing for this computer: nothing can play.
            if (this.playback === playback) fail(playback, DataError.Local.UNKNOWN)
        } finally {
            runCatching { grabber.close() }
        }
    }

    /**
     * The sound's reader: writes each piece to the speakers when the clock says it is due (as heard, after what the
     * line still holds), drops what comes too late, and stays silent while paused, played faster or slower, or seeking.
     */
    private suspend fun playSound(playback: Playback) {
        val grabber = try {
            open(playback.input, forPicture = false)
        } catch (_: Exception) {
            // The picture plays on without sound.
            return
        }
        var line: SourceDataLine? = null
        try {
            val rate = grabber.sampleRate.takeIf { it > 0 } ?: return
            val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
            val bytesPerSecond = rate * 4L
            line = runCatching {
                AudioSystem.getSourceDataLine(format).apply { open(format, (bytesPerSecond * SoundBufferMillis / 1000).toInt() and 3.inv()) }
            }.getOrNull() ?: return // No speakers: silent.
            // From where the picture is now (it may have played muted for a while).
            playback.takeSoundSeek()
            clock.nowUs().takeIf { it > 0 }?.let { grabber.timestamp = it }
            var pending: Sound? = null
            while (currentCoroutineContext().isActive) {
                playback.takeSoundSeek()?.let { targetUs ->
                    grabber.timestamp = targetUs
                    line.stop()
                    line.flush()
                    pending = null
                }
                if (!clock.isRunning || clock.speed != 1f) {
                    if (line.isRunning) line.stop()
                    if (clock.speed != 1f) line.flush()
                    delay(5)
                    if (clock.speed != 1f) {
                        // Off pace: what falls behind the clock is skipped, to be in step when the speed is back to 1.
                        val now = clock.nowUs()
                        while (currentCoroutineContext().isActive) {
                            val next = pending ?: grabber.grabSamples()?.toSound(bytesPerSecond) ?: break
                            if (next.endUs >= now) {
                                pending = next
                                break
                            }
                            pending = null
                        }
                    }
                    continue
                }
                val sound = pending ?: grabber.grabSamples()?.toSound(bytesPerSecond)
                if (sound == null) {
                    // The end (or nothing more until a seek).
                    delay(IdleTickMillis)
                    continue
                }
                val queuedUs = (line.bufferSize - line.available()) * 1_000_000L / bytesPerSecond
                val lateUs = clock.nowUs() + queuedUs - sound.startUs
                when {
                    lateUs > LateSoundUs -> pending = null
                    lateUs < -EarlySoundUs -> {
                        pending = sound
                        delay(2)
                    }
                    else -> {
                        val bytes = sound.pcm(playback.volume)
                        line.write(bytes, 0, bytes.size)
                        if (!line.isRunning) line.start()
                        pending = null
                    }
                }
            }
        } catch (_: Exception) {
            // The sound stops; the picture plays on.
        } finally {
            line?.let {
                runCatching { it.stop() }
                runCatching { it.close() }
            }
            runCatching { grabber.close() }
        }
    }

    private suspend fun fail(playback: Playback, error: DataError.Local) {
        withContext(main) {
            if (this@FfmpegVideoEngine.playback !== playback) return@withContext
            release()
            eventChannel.trySend(VideoEngineEvent.Failed(error))
        }
    }

    /** One loaded video: its readers, and the frames decoded ahead. */
    private class Playback(val input: GrabberInput, val startUs: Long) {
        val jobs = mutableListOf<Job>()
        var hasSound = false
        var isSoundStarted = false
        private val frames = ArrayDeque<VideoFrame>()
        private var pictureSeek: Seek? = null
        private var soundSeek: Long? = null
        private var seeks = 0

        @Volatile var isReady = false
        @Volatile var hasEnded = false
        @Volatile var endReported = false
        @Volatile var pictureEnded = false
        @Volatile var volume = 1f
        @Volatile var generation = 0
        var lastShownUs = 0L

        @Synchronized
        fun seek(targetUs: Long) {
            seeks++
            pictureSeek = Seek(targetUs, seeks)
            soundSeek = targetUs
            pictureEnded = false
            clearFrames()
        }

        @Synchronized
        fun takePictureSeek(): Seek? = pictureSeek.also { pictureSeek = null }

        @Synchronized
        fun takeSoundSeek(): Long? = soundSeek.also { soundSeek = null }

        @Synchronized
        fun offer(frame: VideoFrame) {
            // Decoded from before a seek that came meanwhile: not wanted.
            if (frame.generation != seeks || pictureSeek != null) {
                frame.image.close()
                return
            }
            frames.addLast(frame)
        }

        @Synchronized
        fun queuedFrames(): Int = frames.size

        @Synchronized
        fun peekFrame(): VideoFrame? = frames.firstOrNull()

        @Synchronized
        fun pollFrame(): VideoFrame? = frames.removeFirstOrNull()

        @Synchronized
        fun clearFrames() {
            frames.forEach { it.image.close() }
            frames.clear()
        }
    }

    private class Seek(val targetUs: Long, val generation: Int)

    /** A piece of sound: 16-bit stereo samples, [startUs] to [endUs] into the video. */
    private class Sound(val startUs: Long, val endUs: Long, private val samples: ShortArray) {
        fun pcm(volume: Float): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val sample = if (volume >= 1f) samples[i].toInt() else (samples[i] * volume).toInt()
                bytes[2 * i] = sample.toByte()
                bytes[2 * i + 1] = (sample shr 8).toByte()
            }
            return bytes
        }
    }

    private fun Frame.toSound(bytesPerSecond: Long): Sound? {
        val buffer = samples?.firstOrNull() as? ShortBuffer ?: return null
        val copy = ShortArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        val durationUs = copy.size * 2 * 1_000_000L / bytesPerSecond
        return Sound(timestamp, timestamp + durationUs, copy)
    }
}

/** One decoded picture, [timestampUs] into the video. */
internal class VideoFrame(val timestampUs: Long, val image: Image, val generation: Int)

/** FFmpeg prints every file it opens to the console; only its errors are worth that. */
private val quietFfmpeg: Unit by lazy { av_log_set_level(AV_LOG_ERROR) }

/** Where FFmpeg reads a video from: a path or address it opens itself, or a file on the other device. */
internal sealed interface GrabberInput {
    data class Path(val path: String) : GrabberInput

    class Remote(val source: VideoSource.Remote) : GrabberInput
}

internal fun open(input: String, forPicture: Boolean): FFmpegFrameGrabber = open(GrabberInput.Path(input), forPicture)

internal fun open(input: GrabberInput, forPicture: Boolean): FFmpegFrameGrabber = when (input) {
    is GrabberInput.Path -> FFmpegFrameGrabber(input.path).apply {
        // Over the network: give up on a stalled read instead of hanging.
        if (input.path.startsWith("http")) setOption("rw_timeout", "10000000")
    }
    is GrabberInput.Remote -> FFmpegFrameGrabber(RemoteInputStream(input.source))
}.apply {
    quietFfmpeg
    if (forPicture) {
        pixelFormat = AV_PIX_FMT_BGRA
    } else {
        sampleFormat = AV_SAMPLE_FMT_S16
        audioChannels = 2
    }
    start()
}

/** The frame's pixels, copied (the grabber reuses its buffer) into an image Skia can draw. */
internal fun Frame.toSkiaImage(): Image? {
    val buffer = image?.firstOrNull() as? ByteBuffer ?: return null
    val rowBytes = imageStride
    val bytes = ByteArray(rowBytes * imageHeight)
    val source = buffer.duplicate().apply { position(0) }
    if (source.remaining() < bytes.size) return null
    source.get(bytes)
    return Image.makeRaster(ImageInfo(imageWidth, imageHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), bytes, rowBytes)
}

/** A `file:` URI (what the desktop's file picker and received files use) or a plain path. */
private fun localPath(uri: String): String =
    if (uri.startsWith("file:")) runCatching { File(URI(uri)).path }.getOrDefault(uri) else uri

/**
 * FFmpeg's display rotation is counterclockwise (a portrait phone video: -90); the turn to draw it with is the other
 * way, in quarter turns.
 */
internal fun clockwiseRotation(displayRotation: Double): Int {
    if (displayRotation.isNaN()) return 0
    val quarters = ((-displayRotation) / 90.0).roundToInt()
    return ((quarters % 4 + 4) % 4) * 90
}

/** What FFmpeg's error message says went wrong (`avformat_open_input() error -2: …` is "no such file"). */
internal fun ffmpegErrorToDataError(message: String?): DataError.Local {
    val text = message.orEmpty()
    val code = Regex("""error (-?\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        code == -2 -> DataError.Local.FILE_NOT_FOUND
        code == -13 -> DataError.Local.PERMISSION_DENIED
        "Unsupported" in text || "find_decoder" in text || code == AvErrorInvalidData -> DataError.Local.UNSUPPORTED_FORMAT
        else -> DataError.Local.UNKNOWN
    }
}

/** AVERROR_INVALIDDATA: not a file FFmpeg can read. */
private const val AvErrorInvalidData = -1094995529

