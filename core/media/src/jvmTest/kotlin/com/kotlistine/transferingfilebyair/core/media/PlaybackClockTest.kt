package com.kotlistine.transferingfilebyair.core.media

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import kotlin.test.Test

class PlaybackClockTest {
    private var nanos = 0L
    private val clock = PlaybackClock { nanos }

    private fun advanceMillis(millis: Long) {
        nanos += millis * 1_000_000
    }

    @Test
    fun `a stopped clock stays where it was moved to`() {
        clock.moveTo(5_000)
        advanceMillis(100)
        assertThat(clock.nowUs()).isEqualTo(5_000L)
        assertThat(clock.isRunning).isFalse()
    }

    @Test
    fun `a running clock moves with time, and stops where it got to`() {
        clock.start()
        advanceMillis(250)
        assertThat(clock.nowUs()).isEqualTo(250_000L)
        clock.stop()
        advanceMillis(1_000)
        assertThat(clock.nowUs()).isEqualTo(250_000L)
        clock.start()
        advanceMillis(10)
        assertThat(clock.nowUs()).isEqualTo(260_000L)
        assertThat(clock.isRunning).isTrue()
    }

    @Test
    fun `a new speed applies from now on, not to the time already played`() {
        clock.start()
        advanceMillis(100)
        clock.setSpeed(2f)
        advanceMillis(100)
        assertThat(clock.nowUs()).isEqualTo(300_000L)
    }

    @Test
    fun `moving a running clock keeps it running from there`() {
        clock.start()
        advanceMillis(100)
        clock.moveTo(2_000_000)
        advanceMillis(50)
        assertThat(clock.nowUs()).isEqualTo(2_050_000L)
    }

    @Test
    fun `a phone's portrait video is turned a quarter clockwise`() {
        assertThat(clockwiseRotation(-90.0)).isEqualTo(90)
        assertThat(clockwiseRotation(90.0)).isEqualTo(270)
        assertThat(clockwiseRotation(180.0)).isEqualTo(180)
        assertThat(clockwiseRotation(-180.0)).isEqualTo(180)
        assertThat(clockwiseRotation(0.0)).isEqualTo(0)
        assertThat(clockwiseRotation(Double.NaN)).isEqualTo(0)
    }

    @Test
    fun `FFmpeg's errors map to what went wrong`() {
        assertThat(ffmpegErrorToDataError("avformat_open_input() error -2: Could not open input \"x\"."))
            .isEqualTo(DataError.Local.FILE_NOT_FOUND)
        assertThat(ffmpegErrorToDataError("avformat_open_input() error -13: Could not open input"))
            .isEqualTo(DataError.Local.PERMISSION_DENIED)
        assertThat(ffmpegErrorToDataError("avformat_open_input() error -1094995529: Could not open input"))
            .isEqualTo(DataError.Local.UNSUPPORTED_FORMAT)
        assertThat(ffmpegErrorToDataError("avcodec_find_decoder() error: Unsupported video format"))
            .isEqualTo(DataError.Local.UNSUPPORTED_FORMAT)
        assertThat(ffmpegErrorToDataError(null)).isEqualTo(DataError.Local.UNKNOWN)
    }
}
