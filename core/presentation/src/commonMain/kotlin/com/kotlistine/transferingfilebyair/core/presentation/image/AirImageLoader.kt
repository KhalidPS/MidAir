package com.kotlistine.transferingfilebyair.core.presentation.image

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext

/**
 * The app-wide Coil image loader: images out of the box, plus platform decoders for video frames and
 * the first page of PDFs, so every shelf card can show its real content. Install it with
 * `setSingletonImageLoaderFactory(::newAirImageLoader)` at the root of the UI.
 */
fun newAirImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { addPlatformDecoders() }
        .build()

internal expect fun ComponentRegistry.Builder.addPlatformDecoders()
