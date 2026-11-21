package com.kotlistine.midair.feature.shelf.domain.gesture

import com.kotlistine.midair.core.domain.util.DataError
import kotlinx.coroutines.flow.Flow

/** What the hand in front of the camera is doing. */
enum class HandPose {
    /** No hand in view. */
    NONE,

    /** Fingers spread, palm toward the screen. */
    OPEN,

    /** A clenched fist. */
    FIST,

    /** A hand, but neither open nor a fist (pointing, half-closed on its way between the two…). */
    OTHER,
}

/**
 * One analysed camera picture. [x] and [y] are where the hand's centre is (0..1 across and down the picture, mirrored
 * like a mirror, so a hand moving right moves right), and [size] how much of the picture the hand spans (0..1): a
 * hand right in front of the phone is big, someone walking past in the background is small. Both mean nothing while
 * [pose] is [HandPose.NONE].
 */
data class HandFrame(
    val pose: HandPose,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val size: Float = 0f,
    val atMillis: Long,
)

/** Watches for a hand in front of the screen (the front camera on Android). */
fun interface HandTracker {
    /**
     * Turns the camera on while collected and off when the collection stops, emitting a frame for every picture
     * analysed (a few per second up to the camera's rate). Fails with [HandTrackingException] if the camera can't
     * be used (no permission, no front camera, in use by another app).
     */
    fun frames(): Flow<HandFrame>
}

/** Why the camera can't watch: [DataError.Local.CAMERA_BLOCKED] when the system's privacy settings switched it off. */
class HandTrackingException(
    message: String,
    cause: Throwable? = null,
    val error: DataError.Local = DataError.Local.CAMERA_UNAVAILABLE,
) : Exception(message, cause)
