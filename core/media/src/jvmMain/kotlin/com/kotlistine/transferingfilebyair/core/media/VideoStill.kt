package com.kotlistine.transferingfilebyair.core.media

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The first frame of the video at [path], the right way up, scaled down to fit [maxSide] pixels: a video card's
 * picture on the desktop. Null when it isn't a video FFmpeg can read. Blocks while it decodes.
 */
fun readVideoStill(path: String, maxSide: Int): Bitmap? {
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
        val scale = minOf(1.0, maxSide.toDouble() / max(width, height).coerceAtLeast(1))
        grabber.imageWidth = (width * scale).roundToInt().coerceAtLeast(2)
        grabber.imageHeight = (height * scale).roundToInt().coerceAtLeast(2)
        val rotation = clockwiseRotation(grabber.displayRotation)
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
