package com.kotlistine.transferingfilebyair.core.presentation.image

import coil3.ComponentRegistry
import coil3.video.VideoFrameDecoder

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    add(VideoFrameDecoder.Factory())
    add(PdfPageDecoder.Factory())
}
