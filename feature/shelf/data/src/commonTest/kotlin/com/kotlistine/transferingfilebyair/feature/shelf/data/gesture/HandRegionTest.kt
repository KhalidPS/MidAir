package com.kotlistine.transferingfilebyair.feature.shelf.data.gesture

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.math.PI
import kotlin.test.Test

class HandRegionTest {

    @Test
    fun `a point sent into the model input comes back where it was`() {
        val region = HandRegion(centreX = 300f, centreY = 200f, side = 180f, angle = 0.7f)
        val m = region.toInput(224)
        val (x, y) = 340f to 150f

        val back = region.toPicture(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5], 224)

        assertThat(back.x).isCloseTo(x, 0.01f)
        assertThat(back.y).isCloseTo(y, 0.01f)
    }

    @Test
    fun `the region's centre is the middle of the model input`() {
        val region = HandRegion(centreX = 300f, centreY = 200f, side = 180f, angle = 1.2f)

        val middle = region.toPicture(112f, 112f, 224)

        assertThat(middle.x).isCloseTo(300f, 0.01f)
        assertThat(middle.y).isCloseTo(200f, 0.01f)
    }

    @Test
    fun `a hand pointing right is turned a quarter clockwise, and its square moves toward the fingers`() {
        // Wrist on the left, middle finger's base to its right.
        val palm = Palm(0.9f, left = 100f, top = 100f, right = 200f, bottom = 200f, points = List(7) { i ->
            when (i) {
                0 -> HandPoint(110f, 150f)
                2 -> HandPoint(190f, 150f)
                else -> HandPoint(150f, 150f)
            }
        })

        val region = HandRegion.fromPalm(palm)

        assertThat(region.angle).isCloseTo((PI / 2).toFloat(), 0.001f)
        assertThat(region.centreX).isCloseTo(200f, 0.01f)
        assertThat(region.centreY).isCloseTo(150f, 0.01f)
        assertThat(region.side).isCloseTo(260f, 0.01f)
    }

    @Test
    fun `the palm decoder has the model's 2016 anchors`() {
        assertThat(PalmDecoder().anchorCount).isEqualTo(2016)
    }

    @Test
    fun `the surest anchor above the threshold is the palm, in picture pixels`() {
        val decoder = PalmDecoder()
        val n = decoder.anchorCount
        val scores = FloatArray(n) { -10f }
        val boxes = FloatArray(n * 18)
        // The first anchor sits at (4, 4) in the 192 input; this box is 20 × 10 around (4 + 6, 4 + 2).
        scores[0] = 5f
        boxes[0] = 6f
        boxes[1] = 2f
        boxes[2] = 20f
        boxes[3] = 10f
        // A 384 × 192 picture: half size, with 48 input pixels of padding above.
        val letterbox = Letterbox.fit(width = 384, height = 192, size = 192)

        val palm = decoder.best(boxes, scores, letterbox, minScore = 0.5f)

        assertThat(palm).isNotNull()
        palm!!
        assertThat(palm.left).isCloseTo((10f - 10f) * 2f, 0.01f)
        assertThat(palm.top).isCloseTo((6f - 5f - 48f) * 2f, 0.01f)
        assertThat(palm.right).isCloseTo((10f + 10f) * 2f, 0.01f)
    }

    @Test
    fun `no anchor sure enough means no palm`() {
        val decoder = PalmDecoder()
        val n = decoder.anchorCount

        assertThat(decoder.best(FloatArray(n * 18), FloatArray(n) { -10f }, Letterbox(1f, 0f, 0f), 0.5f)).isNull()
    }
}
