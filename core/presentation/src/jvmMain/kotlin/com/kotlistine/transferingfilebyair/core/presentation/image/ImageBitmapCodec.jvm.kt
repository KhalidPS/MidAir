package com.kotlistine.transferingfilebyair.core.presentation.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/** A transferred card picture is a few hundred pixels a side; a header claiming more is not one of ours. */
private const val MaxDecodedSidePx = 2_048

actual fun ImageBitmap.encodeForTransfer(maxSidePx: Int, quality: Int): ByteArray? = runCatching {
    val source = Image.makeFromBitmap(asSkiaBitmap())
    val scale = maxSidePx.toFloat() / max(source.width, source.height)
    val sized = if (scale < 1f) {
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawImageRect(source, Rect.makeWH(width.toFloat(), height.toFloat()))
        surface.makeImageSnapshot()
    } else {
        source
    }
    sized.encodeToData(EncodedImageFormat.WEBP, quality)?.bytes
}.getOrNull()

/**
 * The bytes come from another device, so the image's declared size is checked before any pixels are allocated: a
 * tiny file can claim enormous dimensions.
 */
actual fun ByteArray.decodeToImageBitmapOrNull(): ImageBitmap? = runCatching {
    val codec = Codec.makeFromData(Data.makeFromBytes(this))
    val tooBig = codec.width > MaxDecodedSidePx || codec.height > MaxDecodedSidePx
    if (codec.width <= 0 || codec.height <= 0 || tooBig) null else Image.makeFromEncoded(this).toComposeImageBitmap()
}.getOrNull()
