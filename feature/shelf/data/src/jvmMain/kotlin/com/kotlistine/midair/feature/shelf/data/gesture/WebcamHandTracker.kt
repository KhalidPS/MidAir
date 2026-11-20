package com.kotlistine.midair.feature.shelf.data.gesture

import com.kotlistine.midair.core.domain.util.DataError
import com.kotlistine.midair.feature.shelf.domain.gesture.HandFrame
import com.kotlistine.midair.feature.shelf.domain.gesture.HandPose
import com.kotlistine.midair.feature.shelf.domain.gesture.HandTracker
import com.kotlistine.midair.feature.shelf.domain.gesture.HandTrackingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.PointerScope
import org.bytedeco.opencv.global.opencv_core.BORDER_CONSTANT
import org.bytedeco.opencv.global.opencv_core.CV_32F
import org.bytedeco.opencv.global.opencv_core.CV_32FC3
import org.bytedeco.opencv.global.opencv_core.CV_64F
import org.bytedeco.opencv.global.opencv_core.copyMakeBorder
import org.bytedeco.opencv.global.opencv_core.flip
import org.bytedeco.opencv.global.opencv_dnn.readNetFromONNX
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2RGB
import org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.global.opencv_imgproc.warpAffine
import org.bytedeco.opencv.global.opencv_videoio.CAP_DSHOW
import org.bytedeco.opencv.global.opencv_videoio.CAP_PROP_FRAME_HEIGHT
import org.bytedeco.opencv.global.opencv_videoio.CAP_PROP_FRAME_WIDTH
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_dnn.Net
import org.bytedeco.opencv.opencv_videoio.VideoCapture
import java.io.File
import java.io.IOException

/** While no hand is in view: 5 pictures a second is enough to notice one coming, and keeps the laptop cool. */
private const val IdleFrameGapMillis = 200L

/** No hand for this long switches to the idle rate. */
private const val IdleAfterMillis = 1_500L

/** The palm detector's square input, in pixels. */
private const val PalmInput = 192

/** The hand model's square input, in pixels. */
private const val HandInput = 224

/** How sure the palm detector must be that it sees a palm. */
private const val MinPalmScore = 0.6f

/** How sure the hand model must be that the square it was shown holds a hand. */
private const val MinHandScore = 0.6f

/**
 * The laptop's webcam, read by MediaPipe's palm detector and hand model (their ONNX builds from OpenCV's model zoo,
 * run by OpenCV). The palm detector finds a hand; after that, each picture looks where the hand was in the last one,
 * so the detector only runs again once the hand is lost. Pictures are mirrored before anything reads them, so a hand
 * moving right moves right, as on the phone's front camera.
 */
class WebcamHandTracker(private val cameraIndex: Int = 0) : HandTracker {

    override fun frames(): Flow<HandFrame> = flow {
        val models = HandModels.load()
        val camera = openCamera()
        if (!camera.isOpened) {
            camera.release()
            models.close()
            throw cameraFailure("No webcam could be opened")
        }
        camera.set(CAP_PROP_FRAME_WIDTH, 640.0)
        camera.set(CAP_PROP_FRAME_HEIGHT, 480.0)
        val picture = Mat()
        val mirrored = Mat()
        var region: HandRegion? = null
        var handSeenAt = 0L
        var lastReadAt = 0L
        try {
            while (currentCoroutineContext().isActive) {
                // Reading drains the webcam's queue even when the picture is skipped, so the next one is fresh.
                if (!camera.read(picture) || picture.empty()) throw cameraFailure("The webcam stopped giving pictures")
                val now = System.currentTimeMillis()
                if (now - handSeenAt > IdleAfterMillis && now - lastReadAt < IdleFrameGapMillis) continue
                lastReadAt = now
                flip(picture, mirrored, 1)
                val (frame, next) = PointerScope().use { models.read(mirrored, region, now) }
                region = next
                if (frame.pose != HandPose.NONE) handSeenAt = now
                emit(frame)
            }
        } finally {
            camera.release()
            picture.close()
            mirrored.close()
            models.close()
        }
    }.flowOn(Dispatchers.IO) // The webcam blocks until each picture is ready.

    /** A blocked webcam either won't open or gives no pictures: the privacy switches tell which failure it is. */
    private fun cameraFailure(message: String) = HandTrackingException(
        message,
        error = if (CameraPrivacy.isBlockedBySystem()) DataError.Local.CAMERA_BLOCKED else DataError.Local.CAMERA_UNAVAILABLE,
    )

    /** On Windows through DirectShow: OpenCV's default there (Media Foundation) takes seconds to open a webcam. */
    private fun openCamera(): VideoCapture {
        val windows = "win" in System.getProperty("os.name").lowercase()
        return if (windows) VideoCapture(cameraIndex, CAP_DSHOW) else VideoCapture(cameraIndex)
    }
}

/** The two models, loaded once per [WebcamHandTracker.frames] collection. Use from one thread at a time. */
private class HandModels(private val palmNet: Net, private val handNet: Net) : AutoCloseable {

    private val decoder = PalmDecoder(PalmInput)

    /** What the hand in [picture] does, and where to look for it in the next one (null: find it again). */
    fun read(picture: Mat, lastRegion: HandRegion?, atMillis: Long): Pair<HandFrame, HandRegion?> {
        val width = picture.cols()
        val height = picture.rows()
        val tracked = lastRegion?.let { points(picture, it) }
        val points = tracked ?: findPalm(picture)?.let { points(picture, HandRegion.fromPalm(it)) }
            ?: return HandFrame(HandPose.NONE, atMillis = atMillis) to null
        return handFrameOf(points, width, height, atMillis) to HandRegion.fromPoints(points)
    }

    private fun findPalm(picture: Mat): Palm? {
        val letterbox = Letterbox.fit(picture.cols(), picture.rows(), PalmInput)
        val small = Mat()
        val width = Math.round(picture.cols() * letterbox.scale)
        val height = Math.round(picture.rows() * letterbox.scale)
        resize(picture, small, Size(width, height))
        val left = (PalmInput - width) / 2
        val top = (PalmInput - height) / 2
        val square = Mat()
        copyMakeBorder(small, square, top, PalmInput - height - top, left, PalmInput - width - left, BORDER_CONSTANT, Scalar(0.0))
        val outputs = run(palmNet, square, PalmInput)
        // Outputs in the model's order: the boxes, then the scores.
        return decoder.best(outputs[0], outputs[1], Letterbox(letterbox.scale, left.toFloat(), top.toFloat()), MinPalmScore)
    }

    /** The hand's 21 points in picture pixels, if the hand model is sure [region] holds a hand. */
    private fun points(picture: Mat, region: HandRegion): List<HandPoint>? {
        val m = region.toInput(HandInput)
        val transform = Mat(2, 3, CV_64F)
        val indexer = transform.createIndexer<org.bytedeco.javacpp.indexer.DoubleIndexer>()
        for (i in 0 until 6) indexer.put((i / 3).toLong(), (i % 3).toLong(), m[i].toDouble())
        indexer.close()
        val crop = Mat()
        warpAffine(picture, crop, transform, Size(HandInput, HandInput), INTER_LINEAR, BORDER_CONSTANT, Scalar(0.0))
        // Outputs in the model's order: 21 points (x, y, depth) in input pixels, then how sure it is of a hand.
        val outputs = run(handNet, crop, HandInput)
        if (outputs[1][0] < MinHandScore) return null
        val raw = outputs[0]
        return List(HandPointCount) { i -> region.toPicture(raw[i * 3], raw[i * 3 + 1], HandInput) }
    }

    /** Runs [net] on a [size]-pixel BGR [square]: RGB, 0..1, one picture with its colours last (1 × size × size × 3). */
    private fun run(net: Net, square: Mat, size: Int): List<FloatArray> {
        val rgb = Mat()
        cvtColor(square, rgb, COLOR_BGR2RGB)
        val scaled = Mat()
        rgb.convertTo(scaled, CV_32FC3, 1.0 / 255.0, 0.0)
        val input = Mat(intArrayOf(1, size, size, 3), CV_32F, scaled.data())
        net.setInput(input)
        val outputs = MatVector()
        net.forward(outputs, net.getUnconnectedOutLayersNames())
        return List(outputs.size().toInt()) { i ->
            val output = outputs.get(i.toLong())
            FloatArray(output.total().toInt()).also { FloatPointer(output.data()).get(it) }
        }
    }

    override fun close() {
        palmNet.close()
        handNet.close()
    }

    companion object {
        fun load(): HandModels = try {
            HandModels(loadNet("palm_detection"), loadNet("hand_landmarks"))
        } catch (e: IOException) {
            throw HandTrackingException("The hand models couldn't be read", e)
        } catch (e: RuntimeException) {
            throw HandTrackingException("The hand models couldn't be loaded", e)
        }

        /** OpenCV reads models from files: the bundled one is copied out once per run of the app. */
        private fun loadNet(name: String): Net {
            val file = File(System.getProperty("java.io.tmpdir"), "air-$name.onnx")
            val bytes = HandModels::class.java.getResourceAsStream("/gesture/$name.onnx")?.use { it.readBytes() }
                ?: throw IOException("$name.onnx isn't bundled")
            if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
            return readNetFromONNX(file.absolutePath)
        }
    }
}
