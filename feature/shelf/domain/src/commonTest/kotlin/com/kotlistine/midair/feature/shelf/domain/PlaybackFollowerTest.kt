package com.kotlistine.midair.feature.shelf.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class PlaybackFollowerTest {

    private val follower = PlaybackFollower()

    @Test
    fun `nothing is known before the first report`() {
        assertThat(follower.targetAt(nowMs = 500)).isNull()
        assertThat(follower.correct(ownPositionMs = 0, nowMs = 500)).isNull()
        assertThat(follower.startAt(nowMs = 500)).isNull()
    }

    @Test
    fun `a playing video's position moves on with time, plus the link's delay`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 20)

        assertThat(follower.targetAt(nowMs = 500)).isEqualTo(1_520)
    }

    @Test
    fun `the least delayed report wins`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 20)
        // Left 300 ms after the first, but arrived 50 ms late.
        follower.report(positionMs = 1_250, isPlaying = true, receivedAtMs = 300, oneWayMs = 20)

        assertThat(follower.targetAt(nowMs = 300)).isEqualTo(1_320)
    }

    @Test
    fun `a paused video stays where it was reported`() {
        follower.report(positionMs = 4_000, isPlaying = false, receivedAtMs = 0, oneWayMs = 20)

        assertThat(follower.targetAt(nowMs = 900)).isEqualTo(4_000)
    }

    @Test
    fun `a sender that stalled is followed from its latest report`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)
        // 600 ms later its video has only moved 100 ms: it was buffering.
        follower.report(positionMs = 1_100, isPlaying = true, receivedAtMs = 600, oneWayMs = 0)

        assertThat(follower.targetAt(nowMs = 600)).isEqualTo(1_100)
    }

    @Test
    fun `a video starts well ahead, since reading it from the peer takes a while`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.startAt(nowMs = 100)).isEqualTo(3_100)
    }

    @Test
    fun `a paused video starts on the reported frame`() {
        follower.report(positionMs = 4_000, isPlaying = false, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.startAt(nowMs = 100)).isEqualTo(4_000)
    }

    @Test
    fun `arriving early it holds its frame until the peer gets there`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.correct(ownPositionMs = 2_100, nowMs = 500)).isEqualTo(PlaybackCorrection.Hold(600))
    }

    @Test
    fun `within a frame of the target it plays at normal speed`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.correct(ownPositionMs = 1_510, nowMs = 500)).isEqualTo(PlaybackCorrection.Speed(1f))
    }

    @Test
    fun `a little ahead it slows down, a little behind it speeds up`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.correct(ownPositionMs = 1_600, nowMs = 500)).isEqualTo(PlaybackCorrection.Speed(0.95f))
        assertThat(follower.correct(ownPositionMs = 1_300, nowMs = 500)).isEqualTo(PlaybackCorrection.Speed(1.1f))
    }

    @Test
    fun `speed changes stay within limits nobody notices`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 990)).isEqualTo(PlaybackCorrection.Speed(1.495f))
        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 1_000)).isEqualTo(PlaybackCorrection.Speed(1.5f))
        assertThat(follower.correct(ownPositionMs = 1_290, nowMs = 0)).isEqualTo(PlaybackCorrection.Speed(0.855f))
        assertThat(follower.correct(ownPositionMs = 1_300, nowMs = 0)).isEqualTo(PlaybackCorrection.Speed(0.85f))
    }

    @Test
    fun `a second and a half behind it still catches up by playing faster`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 1_500)).isEqualTo(PlaybackCorrection.Speed(1.5f))
    }

    @Test
    fun `far behind it jumps ahead of the peer, never just to it`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 2_000)).isEqualTo(PlaybackCorrection.Seek(5_000))
    }

    @Test
    fun `how far ahead it aims is learnt from how long jumps take`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)
        follower.correct(ownPositionMs = 1_000, nowMs = 2_000)

        // That jump took 400 ms to play again: next time it aims 650 ms ahead.
        follower.onPlaying(nowMs = 2_400)

        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 4_000)).isEqualTo(PlaybackCorrection.Seek(5_650))
    }

    @Test
    fun `a slow jump never makes it aim more than a few seconds ahead`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)
        follower.correct(ownPositionMs = 1_000, nowMs = 2_000)

        follower.onPlaying(nowMs = 7_000)

        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 8_000)).isEqualTo(PlaybackCorrection.Seek(12_000))
    }

    @Test
    fun `a slow first load teaches nothing about jumps`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)
        follower.startAt(nowMs = 0)

        follower.onPlaying(nowMs = 5_000)

        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 6_000)).isEqualTo(PlaybackCorrection.Seek(9_000))
    }

    @Test
    fun `a quick jump aims less far next time, but never too close`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)
        follower.correct(ownPositionMs = 1_000, nowMs = 2_000)

        follower.onPlaying(nowMs = 2_050)

        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 3_500)).isEqualTo(PlaybackCorrection.Seek(4_800))
    }

    @Test
    fun `seeing it play again learns nothing when no start was loading`() {
        follower.report(positionMs = 1_000, isPlaying = true, receivedAtMs = 0, oneWayMs = 0)

        follower.onPlaying(nowMs = 5_000)

        assertThat(follower.correct(ownPositionMs = 1_000, nowMs = 2_000)).isEqualTo(PlaybackCorrection.Seek(5_000))
    }

    @Test
    fun `a paused video moves to the reported frame only when it is off`() {
        follower.report(positionMs = 4_000, isPlaying = false, receivedAtMs = 0, oneWayMs = 0)

        assertThat(follower.correct(ownPositionMs = 4_100, nowMs = 10)).isEqualTo(PlaybackCorrection.Seek(4_000))
        assertThat(follower.correct(ownPositionMs = 4_010, nowMs = 10)).isNull()
    }
}
