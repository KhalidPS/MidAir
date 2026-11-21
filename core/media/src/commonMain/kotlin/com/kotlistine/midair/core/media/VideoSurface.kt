package com.kotlistine.midair.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Draws [engine]'s video, cropped to fill the bounds like `ContentScale.Crop`. It follows its layer's transforms and
 * clips, so it can sit in a card that is lifted, tilted and slid.
 *
 * When several surfaces show the same engine, the video goes to the active one composed (or activated) last. A
 * surface that takes over shows the previous one's current frame until its own first frame arrives, so the move
 * never shows a blank or an old frame. [placeholder] is drawn until the first frame when there is nothing to take
 * over from (the video was just loaded).
 */
@Composable
expect fun VideoSurface(
    engine: VideoEngine,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {},
)
