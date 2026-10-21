package com.kotlistine.transferingfilebyair.core.presentation.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.runInterruptible
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Renders the first page of a PDF as a white-backed bitmap sized for the requested thumbnail. */
internal class PdfPageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult = runInterruptible {
        // PdfRenderer needs a seekable file; Coil buffers non-file sources (content URIs) to a temp file.
        val file = source.file().toFile()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val (width, height) = targetSize(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE) // PDF pages are transparent where nothing is drawn.
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    DecodeResult(image = bitmap.asImage(), isSampled = true)
                }
            }
        }
    }

    private fun targetSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        val requestedWidth = options.size.width.pxOrElse { pageWidth * 2 }
        var scale = requestedWidth.toFloat() / pageWidth
        val pixels = pageWidth * scale * pageHeight * scale
        if (pixels > MAX_PIXELS) scale *= sqrt(MAX_PIXELS / pixels)
        return (pageWidth * scale).roundToInt().coerceAtLeast(1) to (pageHeight * scale).roundToInt().coerceAtLeast(1)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (result.mimeType == PDF_MIME_TYPE) PdfPageDecoder(result.source, options) else null
    }

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"

        /** A thumbnail never needs more than ~2 MP (8 MB at ARGB_8888). */
        const val MAX_PIXELS = 2_000_000f
    }
}
