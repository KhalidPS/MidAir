package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import androidx.compose.ui.geometry.Rect
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import kotlin.test.Test

class SlideGeometryTest {

    private val width = 1000f

    /** A 400 px wide card whose right edge is at [right]. */
    private fun cardEndingAt(right: Float) = Rect(right - 400f, 100f, right, 400f)

    @Test
    fun `a card let go inside the screen springs back`() {
        assertThat(decideRelease(cardEndingAt(900f), width, velocityX = 0f)).isEqualTo(ReleaseDecision.Return)
    }

    @Test
    fun `a card let go far enough past the right edge is sent through it`() {
        val atThreshold = cardEndingAt(width + 400f * SendThreshold)

        assertThat(decideRelease(atThreshold, width, velocityX = 0f)).isEqualTo(ReleaseDecision.Send(ExitEdge.RIGHT))
    }

    @Test
    fun `a card let go just short of the threshold springs back`() {
        val short = cardEndingAt(width + 400f * SendThreshold - 10f)

        assertThat(decideRelease(short, width, velocityX = 0f)).isEqualTo(ReleaseDecision.Return)
    }

    @Test
    fun `a card past the left edge is sent through the left edge`() {
        val card = Rect(-200f, 100f, 200f, 400f)

        assertThat(decideRelease(card, width, velocityX = 0f)).isEqualTo(ReleaseDecision.Send(ExitEdge.LEFT))
    }

    @Test
    fun `a flick toward an edge sends a card that has not crossed it yet`() {
        assertThat(decideRelease(cardEndingAt(width), width, velocityX = 2000f))
            .isEqualTo(ReleaseDecision.Send(ExitEdge.RIGHT))
    }

    @Test
    fun `flicking a card back inward keeps it even when it is past the edge`() {
        assertThat(decideRelease(cardEndingAt(1200f), width, velocityX = -2000f)).isEqualTo(ReleaseDecision.Return)
    }

    @Test
    fun `a card where it was lifted feels no pull`() {
        val rest = cardEndingAt(984f)

        assertThat(edgePull(rest, rest, width)).isEqualTo(EdgePull(ExitEdge.RIGHT, 0f))
    }

    @Test
    fun `the pull grows as the card nears the edge and is full at the send threshold`() {
        val rest = cardEndingAt(984f)
        val near = edgePull(cardEndingAt(1030f), rest, width).strength
        val nearer = edgePull(cardEndingAt(1080f), rest, width).strength

        assertThat(near).isGreaterThan(0f)
        assertThat(nearer).isGreaterThan(near)
        assertThat(nearer).isLessThan(1f)
        assertThat(edgePull(cardEndingAt(width + 400f * SendThreshold), rest, width).strength).isEqualTo(1f)
    }

    @Test
    fun `the edge on the card's side of the screen is the one that pulls`() {
        val card = Rect(-40f, 100f, 360f, 400f)

        assertThat(edgePull(card, rest = Rect(16f, 100f, 416f, 400f), width).edge).isEqualTo(ExitEdge.LEFT)
    }

    @Test
    fun `a card carried across the screen feels the far edge only once it gets close`() {
        val leftColumn = Rect(16f, 100f, 416f, 400f)

        assertThat(edgePull(cardEndingAt(720f), leftColumn, width)).isEqualTo(EdgePull(ExitEdge.RIGHT, 0f))
        assertThat(edgePull(cardEndingAt(900f), leftColumn, width).strength).isGreaterThan(0f)
    }
}
