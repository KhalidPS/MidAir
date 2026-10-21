package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import kotlin.coroutines.cancellation.CancellationException

/** The slot's latest layout coordinates, kept outside snapshot state so placement never recomposes anything. */
internal class SlotCoordinates {
    var value: LayoutCoordinates? = null
}

/**
 * Long-press lifts the card into [state]'s overlay; dragging slides it; letting go sends or returns it.
 *
 * Place it after `clickable`: this detector consumes the whole gesture once the card is lifted (moves and the final
 * up), so a lift released without moving doesn't also count as a tap, and the grid doesn't scroll underneath.
 */
internal fun Modifier.liftToSlide(
    state: SlideState,
    key: Any,
    slot: SlotCoordinates,
    onLift: (LayoutCoordinates) -> Boolean,
): Modifier = this
    .onPlaced { slot.value = it }
    .pointerInput(state, key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            press.consume()
            val coordinates = slot.value
            if (coordinates == null || !onLift(coordinates)) {
                // Another card is still in flight: swallow this long-press so its release isn't taken for a tap.
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }

            // The slot sits inside the receding (scaled) shelf, so its local positions don't match the screen.
            // Movement and velocity are measured in overlay coordinates, where the card is drawn, so it stays 1:1
            // with the finger even while the shelf scales down behind it.
            var last = state.toOverlay(coordinates, press.position)
            val tracker = VelocityTracker()
            if (last != null) tracker.addPosition(press.uptimeMillis, last)
            var released = false
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == press.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        released = true
                        break
                    }
                    change.consume()
                    val position = state.toOverlay(coordinates, change.position) ?: break
                    tracker.addPosition(change.uptimeMillis, position)
                    val delta = position - (last ?: position)
                    last = position
                    if (delta != Offset.Zero) state.slideBy(delta)
                }
            } catch (e: CancellationException) {
                state.cancel()
                throw e
            }
            if (released) state.release(tracker.calculateVelocity()) else state.cancel()
        }
    }
