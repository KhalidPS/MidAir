package com.kotlistine.midair.feature.shelf.data.gesture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.kotlistine.midair.feature.shelf.domain.gesture.HandFrame
import com.kotlistine.midair.feature.shelf.domain.gesture.HandPose
import com.kotlistine.midair.feature.shelf.domain.gesture.HandTracker
import com.kotlistine.midair.feature.shelf.domain.gesture.HandTrackingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** MediaPipe's gesture model, bundled in the app's assets. */
private const val ModelAsset = "gesture_recognizer.task"

/** While a hand is in view: 20 pictures a second, so the cursor and the held card keep up with the hand. */
private const val HandFrameGapMillis = 50L

/** While no hand is in view: 5 pictures a second is enough to notice one coming, and keeps the phone cool. */
private const val IdleFrameGapMillis = 200L

/** No hand for this long switches to the idle rate. */
private const val IdleAfterMillis = 1_500L

/** Below this, the model isn't sure enough of the pose to call it open or a fist. */
private const val MinPoseScore = 0.55f

/** Small pictures: a hand at arm's length still spans a good part of them, and the model reads them fast. */
private val AnalysisSize = Size(640, 480)

/**
 * The front camera, read by MediaPipe's gesture recognizer (Open_Palm and Closed_Fist out of the box). The camera
 * runs only while [frames] is collected. Pictures are turned upright and mirrored before the model sees them, so
 * positions match what the user sees when looking at the screen.
 */
class CameraHandTracker(private val context: Context) : HandTracker {

    override fun frames(): Flow<HandFrame> = callbackFlow {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw HandTrackingException("The camera permission isn't granted")

        // When the model last saw a hand (uptime millis); written on the model's thread, read on the camera's.
        val handSeenAt = AtomicLong(0L)
        val recognizer = try {
            withContext(Dispatchers.Default) {
                createRecognizer(
                    onResult = { result ->
                        val frame = result.toFrame()
                        if (frame.pose != HandPose.NONE) handSeenAt.set(SystemClock.uptimeMillis())
                        trySend(frame)
                    },
                    onError = { error -> close(HandTrackingException("The gesture model failed", error)) },
                )
            }
        } catch (e: RuntimeException) {
            throw HandTrackingException("The gesture model couldn't be loaded", e)
        }

        // One thread for every picture: the model is fed in order, and closed on the same thread after the last one.
        val executor = Executors.newSingleThreadExecutor()
        val converter = FrameConverter()
        var lastFedAt = 0L
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(AnalysisSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(executor) { image ->
            val now = SystemClock.uptimeMillis()
            val gap = if (now - handSeenAt.get() < IdleAfterMillis) HandFrameGapMillis else IdleFrameGapMillis
            if (now - lastFedAt < gap) {
                image.close()
                return@setAnalyzer
            }
            lastFedAt = now
            // The model copies the picture when it is fed, so the same bitmap is reused for the next one.
            val upright = image.use(converter::uprightMirrored)
            try {
                recognizer.recognizeAsync(BitmapImageBuilder(upright).build(), now)
            } catch (e: RuntimeException) {
                close(HandTrackingException("The gesture model failed", e))
            }
        }

        val session = CameraSession()
        val provider = awaitCameraProvider()
        try {
            provider.bindToLifecycle(session, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            session.start()
        } catch (e: IllegalArgumentException) {
            close(HandTrackingException("This device has no front camera", e))
        } catch (e: IllegalStateException) {
            close(HandTrackingException("The camera is in use", e))
        }

        awaitClose {
            session.end()
            provider.unbind(analysis)
            analysis.clearAnalyzer()
            executor.execute { recognizer.close() }
            executor.shutdown()
        }
    }.flowOn(Dispatchers.Main.immediate) // CameraX binds and its lifecycle moves on the main thread.

    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(HandTrackingException("The camera couldn't be started", e))
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun createRecognizer(
        onResult: (GestureRecognizerResult) -> Unit,
        onError: (RuntimeException) -> Unit,
    ): GestureRecognizer {
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(ModelAsset).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { error -> onError(error) }
            .build()
        return GestureRecognizer.createFromOptions(context, options)
    }
}

/**
 * Turns camera pictures upright and mirrored, into bitmaps it reuses: allocating two new ones twenty times a second
 * kept the garbage collector busy. Use it from one thread only (the analyzer's).
 */
private class FrameConverter {
    private var source: Bitmap? = null
    private var upright: Bitmap? = null
    private val canvas = Canvas()
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun uprightMirrored(image: ImageProxy): Bitmap {
        val picture = read(image)
        val rotation = image.imageInfo.rotationDegrees
        val sideways = rotation % 180 != 0
        val width = if (sideways) picture.height else picture.width
        val height = if (sideways) picture.width else picture.height
        val out = upright?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { upright = it }
        matrix.reset()
        matrix.postTranslate(-picture.width / 2f, -picture.height / 2f)
        matrix.postRotate(rotation.toFloat())
        // The front camera sees the user's right hand on the left: mirror it, like the user's own view.
        matrix.postScale(-1f, 1f)
        matrix.postTranslate(width / 2f, height / 2f)
        canvas.setBitmap(out)
        canvas.drawBitmap(picture, matrix, paint)
        canvas.setBitmap(null)
        return out
    }

    /** The RGBA picture, copied straight into the reused bitmap when its rows are tightly packed. */
    private fun read(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        if (plane.pixelStride != 4 || plane.rowStride != image.width * 4) return image.toBitmap()
        val bitmap = source?.takeIf { it.width == image.width && it.height == image.height }
            ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { source = it }
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return bitmap
    }
}

private fun GestureRecognizerResult.toFrame(): HandFrame {
    val at = timestampMs()
    val points = landmarks().firstOrNull()
    if (points.isNullOrEmpty()) return HandFrame(HandPose.NONE, atMillis = at)
    val minX = points.minOf { it.x() }
    val maxX = points.maxOf { it.x() }
    val minY = points.minOf { it.y() }
    val maxY = points.maxOf { it.y() }
    return HandFrame(
        pose = gestures().firstOrNull()?.firstOrNull().toPose(),
        x = ((minX + maxX) / 2f).coerceIn(0f, 1f),
        y = ((minY + maxY) / 2f).coerceIn(0f, 1f),
        size = maxOf(maxX - minX, maxY - minY).coerceIn(0f, 1f),
        atMillis = at,
    )
}

private fun Category?.toPose(): HandPose {
    if (this == null || score() < MinPoseScore) return HandPose.OTHER
    return when (categoryName()) {
        "Open_Palm" -> HandPose.OPEN
        "Closed_Fist" -> HandPose.FIST
        else -> HandPose.OTHER
    }
}

/** The camera's own lifecycle: on from [start] until [end], whatever the screen does in between. */
private class CameraSession : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun end() {
        // A lifecycle that never started can't be destroyed; there is nothing to stop either.
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.DESTROYED
    }
}
