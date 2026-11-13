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
    fun `a file that isn't a video has no still`() {
        val notVideo = File(folder, "notes.mp4").apply { writeText("not a video") }
        assertThat(readVideoStill(notVideo.path, maxSide = 160)).isNull()
        assertThat(readVideoStill(File(folder, "gone.mp4").path, maxSide = 160)).isNull()
    }
}
