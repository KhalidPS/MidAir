package com.kotlistine.transferingfilebyair.core.media

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test

/** Plays real files: a short video recorded here with FFmpeg's own encoders (MPEG-4 picture, AAC sound). */
class FfmpegVideoEngineTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = executor.asCoroutineDispatcher()
    private val folder = File(System.getProperty("java.io.tmpdir"), "air-video-test-${System.nanoTime()}").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        executor.shutdownNow()
        folder.deleteRecursively()
    }

    @Test
    fun `a video plays from its first frame to the end`() = runBlocking<Unit> {
        val video = recordVideo(seconds = 1)
        val engine = withContext(ui) { FfmpegVideoEngine(ui) }
        val ended = engine.events
        withContext(ui) { engine.load(VideoSource.Local(video.toURI().toString())) }

        waitUntil { withContext(ui) { engine.frame != null } }
        assertThat(withContext(ui) { engine.durationMs }).isBetween(900L, 1_100L)
        assertThat(withContext(ui) { engine.isPlaying }).isTrue()

        val event = withTimeout(5_000) { ended.first() }
        assertThat(event).isEqualTo(VideoEngineEvent.Ended)
        assertThat(withContext(ui) { engine.isPlaying }).isFalse()
        assertThat(withContext(ui) { engine.positionMs }).isGreaterThanOrEqualTo(900L)
        withContext(ui) { engine.release() }
    }

    @Test
    fun `paused, the picture and the position stay put`() = runBlocking<Unit> {
        val video = recordVideo(seconds = 2)
        val engine = withContext(ui) { FfmpegVideoEngine(ui) }
        withContext(ui) { engine.load(VideoSource.Local(video.toURI().toString()), playWhenReady = false) }

        waitUntil { withContext(ui) { engine.frame != null } }
        delay(300)
        assertThat(withContext(ui) { engine.positionMs }).isEqualTo(0L)
        assertThat(withContext(ui) { engine.isPlaying }).isFalse()

        withContext(ui) { engine.play() }
        delay(300)
        assertThat(withContext(ui) { engine.positionMs }).isBetween(150L, 600L)
        withContext(ui) { engine.release() }
    }

    @Test
    fun `a seek shows the frame from there and plays on`() = runBlocking<Unit> {
        val video = recordVideo(seconds = 2)
        val engine = withContext(ui) { FfmpegVideoEngine(ui) }
        withContext(ui) { engine.load(VideoSource.Local(video.toURI().toString()), playWhenReady = false) }
        waitUntil { withContext(ui) { engine.frame != null } }

        withContext(ui) { engine.seekTo(1_500) }
        waitUntil { withContext(ui) { (engine.frame?.timestampUs ?: 0L) >= 1_400_000L } }
        assertThat(withContext(ui) { engine.positionMs }).isEqualTo(1_500L)
        withContext(ui) { engine.release() }
    }

    @Test
    fun `starting part way in begins there`() = runBlocking<Unit> {
        val video = recordVideo(seconds = 2)
        val engine = withContext(ui) { FfmpegVideoEngine(ui) }
        withContext(ui) { engine.load(VideoSource.Local(video.toURI().toString()), startAtMs = 1_000, playWhenReady = false) }
        waitUntil { withContext(ui) { engine.frame != null } }
        assertThat(withContext(ui) { engine.frame?.timestampUs }).isNotNull().isGreaterThanOrEqualTo(960_000L)
        withContext(ui) { engine.release() }
    }

    @Test
    fun `a portrait phone video is drawn turned upright`() = runBlocking<Unit> {
        val video = recordVideo(seconds = 1, displayRotation = -90.0)
        val engine = withContext(ui) { FfmpegVideoEngine(ui) }
        withContext(ui) { engine.load(VideoSource.Local(video.toURI().toString()), playWhenReady = false) }
        waitUntil { withContext(ui) { engine.frame != null } }
        assertThat(withContext(ui) { engine.rotationDegrees }).isEqualTo(90)
        withContext(ui) { engine.release() }
    }

    @Test
    fun `a missing file fails as not found`() = runBlocking<Unit> {
        val engine = withContext(ui) { FfmpegVideoEngine(ui) }
        val events = engine.events
        withContext(ui) { engine.load(VideoSource.Local(File(folder, "gone.mp4").toURI().toString())) }
        assertThat(withTimeout(2_000) { events.first() }).isEqualTo(VideoEngineEvent.Failed(DataError.Local.FILE_NOT_FOUND))
    }

    @Test
    fun `a file that isn't a video fails as unsupported`() = runBlocking<Unit> {
        val notVideo = File(folder, "notes.mp4").apply { writeText("not a video at all") }
        val engine = withContext(ui) { FfmpegVideoEngine(ui) }
        val events = engine.events
        withContext(ui) { engine.load(VideoSource.Local(notVideo.toURI().toString())) }
        val event = withTimeout(5_000) { events.first() }
        assertThat(event).isInstanceOf(VideoEngineEvent.Failed::class)
        assertThat((event as VideoEngineEvent.Failed).error).isEqualTo(DataError.Local.UNSUPPORTED_FORMAT)
    }

    private suspend fun waitUntil(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    /** A grey picture that brightens frame by frame, over a 440 Hz tone, 25 frames a second. */
    private fun recordVideo(seconds: Int, displayRotation: Double = 0.0): File {
        val file = File(folder, "clip-${System.nanoTime()}.mp4")
        val width = 64
        val height = 48
        val fps = 25
        val sampleRate = 44_100
        FFmpegFrameRecorder(file, width, height, 1).apply {
            format = "mp4"
            videoCodec = AV_CODEC_ID_MPEG4
            audioCodec = AV_CODEC_ID_AAC
            frameRate = fps.toDouble()
            this.sampleRate = sampleRate
            gopSize = fps / 2
            if (displayRotation != 0.0) setDisplayRotation(displayRotation)
            start()
            val samplesPerFrame = sampleRate / fps
            var sampleIndex = 0
            repeat(seconds * fps) { index ->
                val picture = Frame(width, height, Frame.DEPTH_UBYTE, 3)
                val pixels = picture.image[0] as ByteBuffer
                val shade = (index * 255 / (seconds * fps)).toByte()
                for (i in 0 until pixels.capacity()) pixels.put(i, shade)
                record(picture)
                val tone = ShortArray(samplesPerFrame) {
                    (sin(2 * PI * 440 * (sampleIndex + it) / sampleRate) * 8_000).toInt().toShort()
                }
                sampleIndex += samplesPerFrame
                recordSamples(ShortBuffer.wrap(tone))
            }
            stop()
            release()
        }
        return file
    }
}
