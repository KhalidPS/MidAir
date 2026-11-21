package com.kotlistine.midair.core.media

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * A TextureView, not a SurfaceView: it is drawn as part of the Compose layer it sits in, so it scales, tilts and
 * clips with the card. The view keeps the video's own shape and is cropped by its parent, so a picture taken of it
 * ([TextureView.getBitmap]) is the whole frame, never stretched.
 */
@Composable
actual fun VideoSurface(
    engine: VideoEngine,
    isActive: Boolean,
    modifier: Modifier,
    placeholder: @Composable () -> Unit,
) {
    val exo = engine as? ExoVideoEngine
    if (exo == null) {
        Box(modifier) { placeholder() }
    } else {
        ExoSurface(exo, isActive, modifier, placeholder)
    }
}

@Composable
private fun ExoSurface(
    engine: ExoVideoEngine,
    isActive: Boolean,
    modifier: Modifier,
    placeholder: @Composable () -> Unit,
) {
    val output = remember(engine) { engine.newOutput() }
    DisposableEffect(engine, output, isActive) {
        if (isActive) engine.activate(output)
        onDispose { engine.deactivate(output) }
    }
    Box(modifier.clipToBounds()) {
        AndroidView(
            factory = { context ->
                TextureView(context).also { view ->
                    output.view = view
                    view.surfaceTextureListener = OutputListener(engine, output)
                }
            },
            modifier = Modifier.cropToFill { engine.aspectRatio },
            onRelease = { output.view = null },
        )
        if (!output.hasFrame) {
            val cover = output.cover
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                placeholder()
            }
        }
    }
}

private class OutputListener(
    private val engine: ExoVideoEngine,
    private val output: VideoOutput,
) : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
        engine.onSurfaceAvailable(output, surface)

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        engine.onSurfaceDestroyed(output)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = engine.onFrame(output)
}

/**
 * Lays the content out at [aspectRatio] (width / height), just big enough to cover the bounds, and centred: the
 * bounds then show the middle of it, as `ContentScale.Crop` would. Fills the bounds while the ratio is unknown (0).
 */
private fun Modifier.cropToFill(aspectRatio: () -> Float): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val ratio = aspectRatio()
    val (contentWidth, contentHeight) = when {
        ratio <= 0f || width == 0 || height == 0 -> width to height
        ratio > width.toFloat() / height -> (height * ratio).roundToInt() to height
        else -> width to (width / ratio).roundToInt()
    }
    val placeable = measurable.measure(Constraints.fixed(contentWidth, contentHeight))
    layout(width, height) { placeable.place((width - contentWidth) / 2, (height - contentHeight) / 2) }
}
