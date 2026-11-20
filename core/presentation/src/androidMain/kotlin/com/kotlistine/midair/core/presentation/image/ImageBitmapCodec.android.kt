package com.kotlistine.midair.core.presentation.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A transferred card picture is a few hundred pixels a side; a header claiming more is not one of ours. */
private const val MaxDecodedSidePx = 2_048

actual fun ImageBitmap.encodeForTransfer(maxSidePx: Int, quality: Int): ByteArray? {
    val source = asAndroidBitmap()
    // Captured layers are often hardware bitmaps, whose pixels can't be read until copied into memory.
    val readable = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        source
    }
    val scale = maxSidePx.toFloat() / max(readable.width, readable.height)
    val sized = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            readable,
            (readable.width * scale).roundToInt().coerceAtLeast(1),
            (readable.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        readable
    }
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    val out = ByteArrayOutputStream()
    return if (sized.compress(format, quality, out)) out.toByteArray() else null
}

/**
 * The bytes come from another device, so the image's declared size is checked before any pixels are allocated: a
 * tiny file can claim enormous dimensions.
 */
actual fun ByteArray.decodeToImageBitmapOrNull(): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width > MaxDecodedSidePx || height > MaxDecodedSidePx) return null
    return try {
        BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
    } catch (_: OutOfMemoryError) {
        null
    }
}
