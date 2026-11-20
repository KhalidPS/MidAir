package com.kotlistine.midair.core.presentation.image

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.pxOrElse
import com.kotlistine.midair.core.media.readVideoStill
import com.kotlistine.midair.core.media.stillScale
import kotlinx.coroutines.runInterruptible

/** Never decoded bigger than this (longest side, pixels): a card is never shown larger. */
private const val MaxStillSide = 2048

/**
 * A video's first frame, upright, as its card's picture on the desktop (what `coil-video` does on Android).
 *
 * It is sized the way the card shows it: a card that crops its picture gets a frame that covers it. A frame that only
 * fits inside the card is smaller than the card, so the memory cache turns it down the next time the card asks (the
 * lifted copy of the card, a slot coming back on screen) and the frame is decoded again, the card staying blank
 * meanwhile.
 */
internal class VideoStillDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? = runInterruptible {
        // FFmpeg reads from a path; Coil buffers anything else to a file first.
        val path = source.file().toFile().path
        val targetWidth = options.size.width.pxOrElse { -1 }
        val targetHeight = options.size.height.pxOrElse { -1 }
        var scale = 1.0
        val still = readVideoStill(path) { width, height ->
            stillScale(width, height, targetWidth, targetHeight, fill = options.scale == Scale.FILL, MaxStillSide)
                .also { scale = it }
        } ?: return@runInterruptible null
        DecodeResult(image = still.asImage(), isSampled = scale < 1.0)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (result.mimeType?.startsWith("video/") == true) VideoStillDecoder(result.source, options) else null
    }
}
