package com.kotlistine.transferingfilebyair.core.presentation.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Compresses [this] into a small lossy image (longest side at most [maxSidePx]) for sending over a link with a tight
 * per-message limit. Returns null if the platform can't encode it. Call off the main thread.
 */
expect fun ImageBitmap.encodeForTransfer(maxSidePx: Int, quality: Int = 80): ByteArray?

/** Decodes bytes made by [encodeForTransfer]; null if they are not an image. Call off the main thread. */
expect fun ByteArray.decodeToImageBitmapOrNull(): ImageBitmap?
