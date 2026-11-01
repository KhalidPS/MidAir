package com.kotlistine.transferingfilebyair.core.transfer.domain

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import kotlin.test.Test

class CrossingTest {

    private val link = SlideLink(gapMm = 8f)

    private fun move(
        exitEdge: ExitEdge = ExitEdge.RIGHT,
        penetrationMm: Float = 0f,
        alongEdgeMm: Float = 30f,
        outwardMmPerS: Float = 0f,
        alongEdgeMmPerS: Float = 0f,
    ) = SlideMessage.DragMove(
        itemId = "1",
        seq = 0,
        exitEdge = exitEdge,
        alongEdgeMm = alongEdgeMm,
        penetrationMm = penetrationMm,
        outwardMmPerS = outwardMmPerS,
        alongEdgeMmPerS = alongEdgeMmPerS,
        tiltDegrees = 0f,
    )

    @Test
    fun `a card leaving through the right edge shows only its crossed part on the receiver's left edge`() {
        // 20 mm past the sender's edge, 8 mm of it spent crossing the gap: 12 mm of the 50 mm card is on the receiver.
        val position = incomingCardPosition(move(penetrationMm = 20f), cardWidthMm = 50f, receiverWidthMm = 70f, link)

        assertThat(position).isEqualTo(CardPositionMm(leftMm = 12f - 50f, topMm = 30f))
    }

    @Test
    fun `a card leaving through the left edge comes in through the receiver's right edge`() {
        val position = incomingCardPosition(
            move(exitEdge = ExitEdge.LEFT, penetrationMm = 20f),
            cardWidthMm = 50f,
            receiverWidthMm = 70f,
            link,
        )

        assertThat(position).isEqualTo(CardPositionMm(leftMm = 70f - 12f, topMm = 30f))
    }

    @Test
    fun `a card still in the gap is entirely off the receiver's screen`() {
        val position = incomingCardPosition(move(penetrationMm = 5f), cardWidthMm = 50f, receiverWidthMm = 70f, link)

        assertThat(position.leftMm + 50f).isLessThan(0f)
    }

    @Test
    fun `a lower sender screen moves the card down on the receiver`() {
        val offsetLink = link.copy(verticalOffsetMm = 6f)

        val position = incomingCardPosition(move(), cardWidthMm = 50f, receiverWidthMm = 70f, offsetLink)

        assertThat(position.topMm).isEqualTo(36f)
    }

    @Test
    fun `arrival is zero far from the edge and full once the card reaches the receiver`() {
        assertThat(move(penetrationMm = -40f).arrival(link)).isEqualTo(0f)
        assertThat(move(penetrationMm = link.gapMm).arrival(link)).isEqualTo(1f)
        val halfway = move(penetrationMm = link.gapMm - 20f).arrival(link)
        assertThat(halfway).isGreaterThan(0f)
        assertThat(halfway).isLessThan(1f)
    }

    @Test
    fun `prediction moves the card along its velocity`() {
        val predicted = move(penetrationMm = 10f, alongEdgeMm = 30f, outwardMmPerS = 200f, alongEdgeMmPerS = -50f)
            .predictedAhead(seconds = 0.02f)

        assertThat(predicted.penetrationMm).isCloseTo(14f, 0.001f)
        assertThat(predicted.alongEdgeMm).isCloseTo(29f, 0.001f)
    }

    @Test
    fun `a link with a known side only leads to the peer through that edge`() {
        val rightOnly = SlideLink(peerSide = ExitEdge.RIGHT)

        assertThat(rightOnly.leadsToPeer(ExitEdge.RIGHT)).isTrue()
        assertThat(rightOnly.leadsToPeer(ExitEdge.LEFT)).isFalse()
        assertThat(SlideLink().leadsToPeer(ExitEdge.LEFT)).isTrue()
    }

    @Test
    fun `millimetres survive a round trip through pixels`() {
        val density = ScreenDensity(xdpi = 420f, ydpi = 400f)

        assertThat(density.xPxToMm(density.xMmToPx(50f))).isCloseTo(50f, 0.001f)
        assertThat(density.xMmToPx(25.4f)).isEqualTo(420f)
        assertThat(density.yMmToPx(25.4f)).isEqualTo(400f)
    }

    @Test
    fun `the peer's millimetres are turned into the receiver's`() {
        // The peer's millimetres are 10 % longer than the receiver's: its 20 mm are 22 here, its 50 mm card is 55.
        val scaled = SlideLink(gapMm = 8f, peerMmScale = 1.1f)

        val position = incomingCardPosition(move(penetrationMm = 20f), cardWidthMm = 50f, receiverWidthMm = 70f, scaled)

        assertThat(position.leftMm).isCloseTo(22f - 8f - 55f, 0.001f)
        assertThat(position.topMm).isCloseTo(33f, 0.001f)
    }

    @Test
    fun `the card's speed on the receiver points the way it travels there`() {
        val fromRight = incomingCardVelocity(move(outwardMmPerS = 100f, alongEdgeMmPerS = -20f), link)
        val fromLeft = incomingCardVelocity(move(exitEdge = ExitEdge.LEFT, outwardMmPerS = 100f), link)

        assertThat(fromRight).isEqualTo(CardPositionMm(leftMm = 100f, topMm = -20f))
        assertThat(fromLeft.leftMm).isEqualTo(-100f)
    }

}
