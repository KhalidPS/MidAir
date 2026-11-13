package com.kotlistine.transferingfilebyair.core.presentation.image

import coil3.ComponentRegistry

/** Skia decodes the images and FFmpeg the videos' first frames; PDF pages have no desktop decoder yet (their icon shows). */
internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    add(VideoStillDecoder.Factory())
}
