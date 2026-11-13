package com.kotlistine.transferingfilebyair.core.media

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** No player on the desktop yet ([StillVideoEngine]): the placeholder (the video's still) stays. */
@Composable
actual fun VideoSurface(
    engine: VideoEngine,
    isActive: Boolean,
    modifier: Modifier,
    placeholder: @Composable () -> Unit,
) {
    Box(modifier) { placeholder() }
}
