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
import java.io.File
import java.util.concurrent.Executors
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
        val video = recordTestVideo(folder, seconds = 1)
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
        val video = recordTestVideo(folder, seconds = 2)
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
        val video = recordTestVideo(folder, seconds = 2)
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
        val video = recordTestVideo(folder, seconds = 2)
        val engine = withContext(ui) { FfmpegVideoEngine(ui) }
        withContext(ui) { engine.load(VideoSource.Local(video.toURI().toString()), startAtMs = 1_000, playWhenReady = false) }
        waitUntil { withContext(ui) { engine.frame != null } }
        assertThat(withContext(ui) { engine.frame?.timestampUs }).isNotNull().isGreaterThanOrEqualTo(960_000L)
        withContext(ui) { engine.release() }
    }

    @Test
    fun `a portrait phone video is drawn turned upright`() = runBlocking<Unit> {
        val video = recordTestVideo(folder, seconds = 1, displayRotation = -90.0)
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
}
