package com.kotlistine.transferingfilebyair.core.presentation.image

import coil3.ComponentRegistry

/** Skia decodes the images; video frames and PDF pages have no desktop decoder yet, so those cards show their icon. */
internal actual fun ComponentRegistry.Builder.addPlatformDecoders() = Unit
