package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.awaitFrames
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.max

/** Lifted cards grow by this much (the reference lifts to about 1.1×). */
internal const val LiftedScale = 1.08f
private const val MaxTiltDegrees = 8f
private const val TiltDegreesPerThousandDps = 4f
private const val VelocitySmoothingSeconds = 0.06f
private const val FlarePeak = 0.45f
private val MinExitSpeed = 1400.dp

/** Where the bloom sits and how bright it is. [intensity] goes above 1 while a sent card flares. */
internal class Bloom(val edge: ExitEdge, val intensity: Float, val centerY: Float)

/**
 * The lift → slide → send / spring-back choreography of one shelf card.
 *
 * A lifted card is drawn by the screen's overlay (not inside the grid), so it can leave the grid and be clipped at
 * the screen edge. Its grid slot is hidden only once the copy is on screen, and shown again before the copy is
 * removed, so neither hand-off has an empty frame. Every animated value is read in the draw phase.
 */
@Stable
class SlideState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val density: Density,
    private val onSend: (id: String) -> Unit,
) {
    /** The card being carried, drawn in the overlay. Non-null until its animation fully ends, which also guards re-entry. */
    var item: ShelfItemUi? by mutableStateOf(null)
        private set

    /** The grid slot whose preview is hidden while its copy is in the overlay. */
    var hiddenId: String? by mutableStateOf(null)
        private set

    /** Where the card was lifted from, in overlay coordinates. */
    internal var restRect: Rect by mutableStateOf(Rect.Zero)
        private set

    internal val offset = Animatable(Offset.Zero, Offset.VectorConverter)
    internal val lift = Animatable(0f)
    internal val tilt = Animatable(0f)

    /** Everything behind the card: 0 = sharp, 1 = receded (blurred, dimmed). */
    val focus = Animatable(0f)

    /** Turns the edge pull into light. Fades out after a send, while the card is still past the edge. */
    private val glow = Animatable(1f)
    private val flare = Animatable(0f)

    private var overlay: LayoutCoordinates? = null
    private var fingerOffset = Offset.Zero
    private var dragging = false
    private var tiltJob: Job? = null
    private var armed = false
    private var pullStep = 0
    private var hiddenItemLeft = false

    internal fun attachOverlay(coordinates: LayoutCoordinates) {
        overlay = coordinates
    }

    /** Lifts [item] out of the slot at [slot]. Returns false while another card is still in flight. */
    internal fun tryLift(item: ShelfItemUi, slot: LayoutCoordinates): Boolean {
        val overlay = overlay
        if (this.item != null || overlay == null || !overlay.isAttached || !slot.isAttached) return false
        restRect = Rect(overlay.localPositionOf(slot, Offset.Zero), slot.size.toSize())
        this.item = item
        fingerOffset = Offset.Zero
        dragging = true
        armed = false
        pullStep = 0
        hiddenItemLeft = false
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)

        scope.launch {
            offset.snapTo(Offset.Zero)
            launch { lift.animateTo(1f, Motion.pop()) }
            launch { focus.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
            // The copy is drawn over the slot first; the slot hides only once the copy has presented.
            awaitFrames(3)
            if (dragging && this@SlideState.item?.id == item.id) hiddenId = item.id
        }
        tiltJob = scope.launch { tiltWithVelocity() }
        return true
    }

    internal fun slideBy(delta: Offset) {
        if (!dragging) return
        fingerOffset += delta
        val target = fingerOffset
        scope.launch { offset.snapTo(target) }
        feelTheEdge()
    }

    internal fun release(velocity: Velocity) {
        if (!dragging) return
        dragging = false
        tiltJob?.cancel()
        val item = item ?: return
        val width = overlay?.size?.width?.toFloat() ?: return settleBack(velocity)
        when (val decision = decideRelease(cardRect(fingerOffset, LiftedScale), width, velocity.x)) {
            is ReleaseDecision.Send -> send(item, decision.edge, velocity, width)
            ReleaseDecision.Return -> settleBack(velocity)
        }
    }

    /** The gesture was taken away (the slot left the screen, the system cancelled the touch): always spring back. */
    internal fun cancel() {
        if (!dragging) return
        dragging = false
        tiltJob?.cancel()
        settleBack(Velocity.Zero)
    }

    /**
     * Keeps a sent card's slot hidden while the grid fades it out, and shows it again only if the item comes back
     * (undo), so the card never reappears where it was lifted from.
     */
    internal fun followShelf(items: List<ShelfItemUi>) {
        val id = hiddenId ?: return
        val onShelf = items.any { it.id == id }
        if (!onShelf) {
            hiddenItemLeft = true
        } else if (hiddenItemLeft) {
            hiddenItemLeft = false
            hiddenId = null
        }
    }

    /** [local] (a position inside [slot]) in overlay coordinates, where the carried card is drawn. */
    internal fun toOverlay(slot: LayoutCoordinates, local: Offset): Offset? {
        val overlay = overlay?.takeIf { it.isAttached } ?: return null
        return if (slot.isAttached) overlay.localPositionOf(slot, local) else null
    }

    /** The card as currently drawn (ignoring tilt), in overlay coordinates. Reads animated values. */
    internal fun currentCard(): Rect = cardRect(offset.value, 1f + (LiftedScale - 1f) * lift.value)

    /** Reads animated values: call it from the draw phase. */
    internal fun bloom(containerWidth: Float): Bloom? {
        if (item == null) return null
        val card = currentCard()
        val pull = edgePull(card, restRect.scaledBy(LiftedScale), containerWidth)
        return Bloom(pull.edge, pull.strength * glow.value + flare.value, card.center.y)
    }

    private fun send(item: ShelfItemUi, edge: ExitEdge, velocity: Velocity, width: Float) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        hiddenId = item.id
        scope.launch {
            val light = listOf(
                launch {
                    flare.animateTo(FlarePeak, tween(160, easing = FastOutSlowInEasing))
                    flare.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
                },
                launch {
                    delay(200)
                    glow.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
                },
                launch {
                    delay(160)
                    focus.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
                },
            )
            // The card keeps its momentum out through the edge. The target is well past the edge, so a critically
            // damped spring carries the release velocity without ever settling (or bouncing) in view.
            val start = offset.value
            val card = cardRect(start, LiftedScale)
            val clearance = card.width * 0.3f + with(density) { 32.dp.toPx() }
            val direction = if (edge == ExitEdge.RIGHT) 1f else -1f
            val distance = if (edge == ExitEdge.RIGHT) width - card.left + clearance else card.right + clearance
            val speed = max(velocity.x * direction, with(density) { MinExitSpeed.toPx() })
            offset.animateTo(
                targetValue = start + Offset(direction * distance, velocity.y * 0.08f),
                animationSpec = spring(dampingRatio = 1f, stiffness = 420f),
                initialVelocity = Offset(direction * speed, velocity.y),
            )
            // Off screen: the grid closes the gap behind the clearing blur.
            onSend(item.id)
            light.joinAll()
            reset()
        }
    }

    private fun settleBack(velocity: Velocity) {
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        scope.launch {
            listOf(
                launch { focus.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) },
                launch { tilt.animateTo(0f, Motion.preciseLanding()) },
                launch { lift.animateTo(0f, Motion.preciseLanding()) },
                launch {
                    offset.animateTo(Offset.Zero, Motion.preciseLanding(), Offset(velocity.x, velocity.y))
                },
            ).joinAll()
            // Show the slot under the copy before removing the copy.
            hiddenId = null
            awaitFrames(2)
            reset()
        }
    }

    private suspend fun reset() {
        item = null
        offset.snapTo(Offset.Zero)
        lift.snapTo(0f)
        tilt.snapTo(0f)
        glow.snapTo(1f)
        flare.snapTo(0f)
    }

    /** Leans the card into its motion, from a smoothed per-frame velocity, so it rights itself when the finger rests. */
    private suspend fun tiltWithVelocity() {
        var last = fingerOffset
        var lastNanos = withFrameNanos { it }
        var velocityX = 0f
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceAtLeast(0.001f)
            val current = fingerOffset
            velocityX += ((current.x - last.x) / dt - velocityX) * (1f - exp(-dt / VelocitySmoothingSeconds))
            last = current
            lastNanos = now
            val thousandDps = velocityX / density.density / 1000f
            tilt.snapTo((thousandDps * TiltDegreesPerThousandDps).coerceIn(-MaxTiltDegrees, MaxTiltDegrees))
        }
    }

    /** A threshold click when the card is far enough out to send (and back), with ticks rising on the way there. */
    private fun feelTheEdge() {
        val width = overlay?.size?.width?.toFloat() ?: return
        val card = cardRect(fingerOffset, LiftedScale)
        val nowArmed = decideRelease(card, width, velocityX = 0f) is ReleaseDecision.Send
        if (nowArmed != armed) {
            armed = nowArmed
            haptics.performHapticFeedback(
                if (nowArmed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick,
            )
            return
        }
        val step = (edgePull(card, restRect.scaledBy(LiftedScale), width).strength * 3f).toInt()
        if (!armed && step > pullStep) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        pullStep = step
    }

    private fun cardRect(offset: Offset, scale: Float): Rect = restRect.translate(offset).scaledBy(scale)
}

private fun Rect.scaledBy(scale: Float): Rect {
    val dx = width * (scale - 1f) / 2f
    val dy = height * (scale - 1f) / 2f
    return Rect(left - dx, top - dy, right + dx, bottom + dy)
}

@Composable
internal fun rememberSlideState(onSend: (id: String) -> Unit): SlideState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentOnSend by rememberUpdatedState(onSend)
    return remember(scope, haptics, density) {
        SlideState(scope, haptics, density, onSend = { currentOnSend(it) })
    }
}
