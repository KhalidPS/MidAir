package com.kotlistine.midair.core.media

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import kotlin.math.max

private val Smooth = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

/**
 * Draws the [FfmpegVideoEngine]'s current frame, turned the right way up and cropped to fill. The frame is read while
 * drawing only, so a new one redraws this surface without recomposing anything. While active and the video advances,
 * it moves the engine on to the frame due at every frame it draws.
 *
 * Every active surface of an engine shows the same frame, so a card taking the video over from another never shows a
 * gap. An inactive one shows [placeholder], as does one whose engine has no frame yet.
 */
@Composable
actual fun VideoSurface(
    engine: VideoEngine,
    isActive: Boolean,
    modifier: Modifier,
    placeholder: @Composable () -> Unit,
) {
    val ffmpeg = engine as? FfmpegVideoEngine
    if (ffmpeg == null) {
        Box(modifier) { placeholder() }
        return
    }
    if (isActive) {
        LaunchedEffect(ffmpeg) {
            snapshotFlow { ffmpeg.isAdvancing }.collectLatest { advancing ->
                if (advancing) while (true) withFrameNanos { ffmpeg.tick() }
            }
        }
    }
    val hasFrame by remember(ffmpeg) { derivedStateOf { ffmpeg.frame != null } }
    Box(
        modifier
            .clipToBounds()
            .drawBehind { if (isActive) drawFrame(ffmpeg) },
    ) {
        if (!isActive || !hasFrame) placeholder()
    }
}

private fun DrawScope.drawFrame(engine: FfmpegVideoEngine) {
    val frame = engine.frame ?: return
    val image = frame.image
    val rotation = engine.rotationDegrees
    val turned = rotation % 180 != 0
    val shownWidth = if (turned) image.height else image.width
    val shownHeight = if (turned) image.width else image.height
    // Like ContentScale.Crop: just big enough to cover, centred.
    val scale = max(size.width / shownWidth, size.height / shownHeight)
    drawIntoCanvas { canvas ->
        val skia = canvas.nativeCanvas
        skia.save()
        skia.translate(size.width / 2f, size.height / 2f)
        skia.rotate(rotation.toFloat())
        skia.scale(scale, scale)
        val halfWidth = image.width / 2f
        val halfHeight = image.height / 2f
        skia.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect(-halfWidth, -halfHeight, halfWidth, halfHeight),
            Smooth,
            null,
            true,
        )
        skia.restore()
    }
}
