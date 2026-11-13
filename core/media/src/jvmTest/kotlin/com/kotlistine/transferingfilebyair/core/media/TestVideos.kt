package com.kotlistine.transferingfilebyair.core.media

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * A short video recorded into [folder] with FFmpeg's own encoders (MPEG-4 picture, AAC sound): a [width] × [height]
 * grey picture that brightens frame by frame, over a 440 Hz tone, 25 frames a second. [displayRotation] is stored the
 * way a phone stores it (counterclockwise; a portrait video: -90).
 */
internal fun recordTestVideo(
    folder: File,
    seconds: Int,
    displayRotation: Double = 0.0,
    width: Int = 64,
    height: Int = 48,
): File {
    val file = File(folder, "clip-${System.nanoTime()}.mp4")
    val fps = 25
    val sampleRate = 44_100
    FFmpegFrameRecorder(file, width, height, 1).apply {
        format = "mp4"
        videoCodec = AV_CODEC_ID_MPEG4
        audioCodec = AV_CODEC_ID_AAC
        frameRate = fps.toDouble()
        this.sampleRate = sampleRate
        gopSize = fps / 2
        if (displayRotation != 0.0) setDisplayRotation(displayRotation)
        start()
        val samplesPerFrame = sampleRate / fps
        var sampleIndex = 0
        repeat(seconds * fps) { index ->
            val picture = Frame(width, height, Frame.DEPTH_UBYTE, 3)
            val pixels = picture.image[0] as ByteBuffer
            val shade = (index * 255 / (seconds * fps)).toByte()
            for (i in 0 until pixels.capacity()) pixels.put(i, shade)
            record(picture)
            val tone = ShortArray(samplesPerFrame) {
                (sin(2 * PI * 440 * (sampleIndex + it) / sampleRate) * 8_000).toInt().toShort()
            }
            sampleIndex += samplesPerFrame
            recordSamples(ShortBuffer.wrap(tone))
        }
        stop()
        release()
    }
    return file
}
