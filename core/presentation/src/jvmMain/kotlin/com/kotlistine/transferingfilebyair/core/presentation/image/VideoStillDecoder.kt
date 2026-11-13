package com.kotlistine.transferingfilebyair.core.presentation.image

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import com.kotlistine.transferingfilebyair.core.media.readVideoStill
import kotlinx.coroutines.runInterruptible

/** Never decoded bigger than this (longest side, pixels): a card is never shown larger. */
private const val MaxStillSide = 960

/** A video's first frame, upright, as its card's picture on the desktop (what `coil-video` does on Android). */
internal class VideoStillDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? = runInterruptible {
        // FFmpeg reads from a path; Coil buffers anything else to a file first.
        val path = source.file().toFile().path
        val requested = maxOf(options.size.width.pxOrElse { MaxStillSide }, options.size.height.pxOrElse { MaxStillSide })
        val still = readVideoStill(path, requested.coerceIn(1, MaxStillSide)) ?: return@runInterruptible null
        DecodeResult(image = still.asImage(), isSampled = true)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (result.mimeType?.startsWith("video/") == true) VideoStillDecoder(result.source, options) else null
    }
}
