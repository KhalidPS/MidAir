package com.kotlistine.midair.core.media

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The first frame of the video at [path], the right way up, scaled down to fit [maxSide] pixels. Null when it isn't
 * a video FFmpeg can read. Blocks while it decodes.
 */
fun readVideoStill(path: String, maxSide: Int): Bitmap? =
    readVideoStill(path) { width, height -> minOf(1.0, maxSide.toDouble() / max(width, height).coerceAtLeast(1)) }

/**
 * The first frame of the video at [path], the right way up, scaled by [scaleFor] (given the upright frame's width and
 * height): a video card's picture on the desktop. Null when it isn't a video FFmpeg can read. Blocks while it decodes.
 */
fun readVideoStill(path: String, scaleFor: (uprightWidth: Int, uprightHeight: Int) -> Double): Bitmap? {
    val grabber = try {
        open(path, forPicture = true)
    } catch (_: Exception) {
        return null
    } catch (_: LinkageError) {
        return null
    }
    return try {
        if (!grabber.hasVideo()) return null
        val width = grabber.imageWidth
        val height = grabber.imageHeight
        val rotation = clockwiseRotation(grabber.displayRotation)
        val turned = rotation % 180 != 0
        val scale = if (turned) scaleFor(height, width) else scaleFor(width, height)
        grabber.imageWidth = (width * scale).roundToInt().coerceAtLeast(2)
        grabber.imageHeight = (height * scale).roundToInt().coerceAtLeast(2)
        val frame = grabber.grabImage() ?: return null
        frame.toSkiaImage()?.use { it.upright(rotation) }
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    } finally {
        runCatching { grabber.close() }
    }
}

/**
 * How much an upright still [width] × [height] is scaled to show in [targetWidth] × [targetHeight]: just enough to cover
 * it when [fill] (a card that crops its picture), else to fit inside it. A side that isn't known (≤ 0) doesn't count,
 * and with neither known the still keeps its size. Never more than [maxSide] on its longest side.
 */
fun stillScale(width: Int, height: Int, targetWidth: Int, targetHeight: Int, fill: Boolean, maxSide: Int): Double {
    val across = if (targetWidth > 0) targetWidth / width.coerceAtLeast(1).toDouble() else null
    val down = if (targetHeight > 0) targetHeight / height.coerceAtLeast(1).toDouble() else null
    val scale = when {
        across != null && down != null -> if (fill) max(across, down) else minOf(across, down)
        else -> across ?: down ?: 1.0
    }
    return minOf(scale, maxSide / max(width, height).coerceAtLeast(1).toDouble())
}

/** This image drawn turned [rotation] degrees clockwise, into a bitmap of its own. */
private fun Image.upright(rotation: Int): Bitmap {
    val turned = rotation % 180 != 0
    val outWidth = if (turned) height else width
    val outHeight = if (turned) width else height
    val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32(outWidth, outHeight, ColorAlphaType.OPAQUE)) }
    Canvas(bitmap).use { canvas ->
        canvas.translate(outWidth / 2f, outHeight / 2f)
        canvas.rotate(rotation.toFloat())
        canvas.drawImage(this, -width / 2f, -height / 2f)
    }
    bitmap.setImmutable()
    return bitmap
}
