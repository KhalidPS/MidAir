package com.kotlistine.transferingfilebyair.core.media

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

class VideoStillTest {
    private val folder = File(System.getProperty("java.io.tmpdir"), "air-still-test-${System.nanoTime()}").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun `a video's still is its first frame, scaled down to fit`() {
        val video = recordTestVideo(folder, seconds = 1, width = 320, height = 240)
        val still = readVideoStill(video.path, maxSide = 160)
        assertThat(still).isNotNull()
        assertThat(still!!.width).isEqualTo(160)
        assertThat(still.height).isEqualTo(120)
    }

    @Test
    fun `a portrait phone video's still stands upright`() {
        val video = recordTestVideo(folder, seconds = 1, displayRotation = -90.0, width = 320, height = 240)
        val still = readVideoStill(video.path, maxSide = 320)
        assertThat(still).isNotNull()
        assertThat(still!!.width).isEqualTo(240)
        assertThat(still.height).isEqualTo(320)
    }

    @Test
    fun `a still for a card that crops it covers the whole card`() {
        val video = recordTestVideo(folder, seconds = 1, width = 320, height = 240)
        val still = readVideoStill(video.path) { width, height -> stillScale(width, height, 100, 200, fill = true, 2048) }
        assertThat(still).isNotNull()
        assertThat(still!!.width).isEqualTo(267)
        assertThat(still.height).isEqualTo(200)
    }

    @Test
    fun `a portrait phone video's still is sized upright`() {
        val video = recordTestVideo(folder, seconds = 1, displayRotation = -90.0, width = 320, height = 240)
        val still = readVideoStill(video.path) { width, height -> stillScale(width, height, 120, 120, fill = true, 2048) }
        assertThat(still).isNotNull()
        assertThat(still!!.width).isEqualTo(120)
        assertThat(still.height).isEqualTo(160)
    }

    @Test
    fun `a still fits inside the target when it isn't cropped`() {
        assertThat(stillScale(320, 240, 100, 200, fill = false, maxSide = 2048)).isEqualTo(100 / 320.0)
    }

    @Test
    fun `a still is scaled by the one side known, or kept as it is`() {
        assertThat(stillScale(320, 240, -1, 120, fill = true, maxSide = 2048)).isEqualTo(0.5)
        assertThat(stillScale(320, 240, -1, -1, fill = true, maxSide = 2048)).isEqualTo(1.0)
    }

    @Test
    fun `a still never grows past the longest side allowed`() {
        assertThat(stillScale(320, 240, 2000, 4000, fill = true, maxSide = 640)).isEqualTo(2.0)
    }

    @Test
    fun `a file that isn't a video has no still`() {
        val notVideo = File(folder, "notes.mp4").apply { writeText("not a video") }
        assertThat(readVideoStill(notVideo.path, maxSide = 160)).isNull()
        assertThat(readVideoStill(File(folder, "gone.mp4").path, maxSide = 160)).isNull()
    }
}
