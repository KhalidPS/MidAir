package com.kotlistine.transferingfilebyair.feature.shelf.data.gesture

import kotlin.math.exp

/** A palm found in a picture, in its pixels: the box around it, and its seven points (0 the wrist, 2 the middle finger's base). */
data class Palm(
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val points: List<HandPoint>,
)

/**
 * How a picture was shrunk and padded into the palm model's square input: [scale] input pixels per picture pixel,
 * with [padX] and [padY] input pixels of padding left and above.
 */
data class Letterbox(val scale: Float, val padX: Float, val padY: Float) {
    fun toPicture(inputX: Float, inputY: Float): HandPoint = HandPoint((inputX - padX) / scale, (inputY - padY) / scale)

    companion object {
        /** A [width] × [height] picture shrunk to fit a [size] square, centred. */
        fun fit(width: Int, height: Int, size: Int): Letterbox {
            val scale = size.toFloat() / maxOf(width, height)
            return Letterbox(scale, padX = (size - width * scale) / 2f, padY = (size - height * scale) / 2f)
        }
    }
}

/** The values the palm model gives for each anchor: a box (centre x, y, width, height) and seven (x, y) points. */
private const val ValuesPerAnchor = 18

/**
 * Reads the output of MediaPipe's palm detector (an [inputSize]-pixel square input): one box and score per anchor, the
 * anchors laid on a 24 × 24 grid (2 each) and a 12 × 12 grid (6 each), each box given relative to its anchor.
 */
class PalmDecoder(private val inputSize: Int = 192) {

    /** Every anchor's centre, x then y, as fractions of the input. */
    private val anchors: FloatArray = buildList {
        for ((grid, perCell) in listOf(inputSize / 8 to 2, inputSize / 16 to 6)) {
            for (y in 0 until grid) for (x in 0 until grid) repeat(perCell) {
                add((x + 0.5f) / grid)
                add((y + 0.5f) / grid)
            }
        }
    }.toFloatArray()

    val anchorCount: Int get() = anchors.size / 2

    /**
     * The surest palm in [boxes] ([anchorCount] × 18 values) and [scores] ([anchorCount] raw scores), at least [minScore]
     * sure, in the pixels of the picture [letterbox] fitted into the input; null when there is none.
     */
    fun best(boxes: FloatArray, scores: FloatArray, letterbox: Letterbox, minScore: Float): Palm? {
        require(scores.size == anchorCount && boxes.size == anchorCount * ValuesPerAnchor) { "Not this model's output" }
        var best = -1
        var bestScore = minScore
        for (i in 0 until anchorCount) {
            val score = sigmoid(scores[i])
            if (score >= bestScore) {
                best = i
                bestScore = score
            }
        }
        if (best < 0) return null
        val at = best * ValuesPerAnchor
        val anchorX = anchors[best * 2] * inputSize
        val anchorY = anchors[best * 2 + 1] * inputSize
        val centreX = anchorX + boxes[at]
        val centreY = anchorY + boxes[at + 1]
        val halfWidth = boxes[at + 2] / 2f
        val halfHeight = boxes[at + 3] / 2f
        val topLeft = letterbox.toPicture(centreX - halfWidth, centreY - halfHeight)
        val bottomRight = letterbox.toPicture(centreX + halfWidth, centreY + halfHeight)
        val points = List(7) { p -> letterbox.toPicture(anchorX + boxes[at + 4 + p * 2], anchorY + boxes[at + 5 + p * 2]) }
        return Palm(bestScore, topLeft.x, topLeft.y, bottomRight.x, bottomRight.y, points)
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x.coerceIn(-80f, 80f)))
}
