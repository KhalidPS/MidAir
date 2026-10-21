package com.kotlistine.transferingfilebyair.core.designsystem.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.withFrameNanos

// Specs. Names describe the *role*, so choreography code reads like the script it came from.
object Motion {
    /** Backdrop/panel sliding in: arrives firmly with a tiny bump (~3.8 %). Also used for grid reflow. */
    fun <T> slideIn(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.72f, stiffness = 170f)

    /** Size pop on arrival (~12.6 % of the change). */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 190f)

    /** Lands precisely — use when another view must take over at the exact end position. */
    fun <T> preciseLanding(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 220f)

    /** Screen progress clock going forward. */
    fun <T> enter(durationMs: Int = 460): FiniteAnimationSpec<T> = tween(durationMs, easing = FastOutSlowInEasing)

    /** Leaving: accelerates away, no landing. Never use a spring for an exit. */
    fun <T> exit(durationMs: Int = 300): FiniteAnimationSpec<T> = tween(durationMs, easing = FastOutLinearInEasing)
}

/**
 * Remaps a shared 0→1 progress so this element starts at [delay] (a fraction of the progress)
 * and still finishes at 1 — later elements move faster over less time, which keeps a cascade
 * snappy.
 */
fun stagger(progress: Float, delay: Float): Float =
    if (delay >= 1f) (if (progress >= 1f) 1f else 0f)
    else ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)

/** Old content leaves fast and upward; new content enters from below after a short beat. */
fun textSwap(): ContentTransform =
    (fadeIn(tween(260, delayMillis = 90)) + slideInVertically(tween(320)) { it / 3 })
        .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 4 })

/** Waits [frames] frames — lets the first frame draw before expensive or animated work starts. */
suspend fun awaitFrames(frames: Int = 2) = repeat(frames) { withFrameNanos { } }
