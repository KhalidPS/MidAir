package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
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
import com.kotlistine.transferingfilebyair.core.presentation.image.encodeForTransfer
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max

/** Lifted cards grow by this much (the reference lifts to about 1.1×). */
internal const val LiftedScale = 1.08f
private const val MaxTiltDegrees = 8f
private const val TiltDegreesPerThousandDps = 4f
private const val VelocitySmoothingSeconds = 0.06f
private const val FlarePeak = 0.45f
private val MinExitSpeed = 1400.dp

/** A sent card's sideways drift dies out like this, so it leaves along the edge's normal. */
private const val ExitDriftSeconds = 0.08f

/** How far past the edge a sent card goes before it counts as gone (its shadow and tilted corners too). */
private val ExitClearance = 16.dp

/**
 * The card picture sent to the peer must fit in one message (32 KB) with room for the rest of it. Busy pictures are
 * tried again smaller and softer; past the last attempt the card goes without one (the peer draws a plain card).
 */
private const val ThumbnailMaxBytes = 24 * 1024
private val ThumbnailAttempts = listOf(320 to 80, 256 to 70, 192 to 55)

private fun ImageBitmap.encodeWithinBudget(): ByteArray? =
    ThumbnailAttempts.firstNotNullOfOrNull { (maxSide, quality) ->
        encodeForTransfer(maxSide, quality)?.takeIf { it.size <= ThumbnailMaxBytes }
    }

/** Where the bloom sits and how bright it is. [intensity] goes above 1 while a sent card flares. */
internal class Bloom(val edge: ExitEdge, val intensity: Float, val centerY: Float)

/** Everything the carried card does, for mirroring it on the device next door. All geometry is in pixels. */
interface SlideListener {
    /** A card was lifted. [thumbnail] is the card as drawn, compressed; null when it couldn't be captured. */
    fun onLifted(id: String, cardWidthPx: Float, cardHeightPx: Float, thumbnail: ByteArray?)

    /** Every frame from lift until the card is gone, including its flight out after a send. */
    fun onMoved(frame: SlideFrame)

    /** Let go short of the edge: it sprang back into its slot. */
    fun onReturned(id: String)

    /** It left through [edge] and is now fully off screen. */
    fun onSent(id: String, edge: ExitEdge)
}

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
    private val listener: SlideListener,
    /** Whether a peer is watching: only then is the card captured and every frame reported. */
    private val isMirrored: () -> Boolean,
) {
    /** The card being carried, drawn in the overlay. Non-null until its animation fully ends, which also guards re-entry. */
    var item: ShelfItemUi? by mutableStateOf(null)
        private set

    /**
     * The card is in the air: lifted, carried, or on its way back or out. It turns false just before the copy lands
     * back in its slot, so the slot takes a playing video back while the copy still covers it.
     */
    internal var isAloft by mutableStateOf(false)
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

    /** The overlay card's recording, set by [LiftedCard]; captured once after lift for the peer. */
    internal var cardLayer: GraphicsLayer? = null

    private var overlay: LayoutCoordinates? = null
    private var fingerOffset = Offset.Zero
    private var dragging = false
    private var trackJob: Job? = null
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
        isAloft = true
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
        val mirrored = isMirrored()
        if (mirrored) scope.launch { announce(item) }
        trackJob = scope.launch { track(item.id, report = mirrored) }
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
        val item = item ?: return
        val width = overlay?.size?.width?.toFloat() ?: return settleBack(item, velocity)
        when (val decision = decideRelease(cardRect(fingerOffset, LiftedScale), width, velocity.x)) {
            is ReleaseDecision.Send -> send(item, decision.edge, velocity, width)
            ReleaseDecision.Return -> settleBack(item, velocity)
        }
    }

    /** The gesture was taken away (the slot left the screen, the system cancelled the touch): always spring back. */
    internal fun cancel() {
        if (!dragging) return
        dragging = false
        item?.let { settleBack(it, Velocity.Zero) }
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
            flyOut(edge, velocity, width)
            // Off screen: the peer takes the card over at the speed it left here, and the grid closes the gap behind
            // the clearing blur.
            trackJob?.cancel()
            listener.onSent(item.id, edge)
            light.joinAll()
            reset()
        }
    }

    /**
     * The card keeps its momentum out through [edge] at a steady speed (at least [MinExitSpeed]) until it is fully
     * off screen. Steady, so the peer, which sees every frame of it, carries on at the same speed: one motion across
     * the two screens, not an exit and a separate arrival.
     */
    private suspend fun flyOut(edge: ExitEdge, velocity: Velocity, width: Float) {
        val start = offset.value
        val card = cardRect(start, LiftedScale)
        val clearance = card.width * 0.1f + with(density) { ExitClearance.toPx() }
        val direction = if (edge == ExitEdge.RIGHT) 1f else -1f
        val distance = if (edge == ExitEdge.RIGHT) width - card.left + clearance else card.right + clearance
        val speed = max(velocity.x * direction, with(density) { MinExitSpeed.toPx() })
        var travelled = 0f
        var drift = 0f
        var driftSpeed = velocity.y
        var lastNanos = withFrameNanos { it }
        while (travelled < distance) {
            val now = withFrameNanos { it }
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastNanos = now
            travelled = (travelled + speed * dt).coerceAtMost(distance)
            drift += driftSpeed * dt
            driftSpeed *= exp(-dt / ExitDriftSeconds)
            offset.snapTo(start + Offset(direction * travelled, drift))
        }
    }

    private fun settleBack(item: ShelfItemUi, velocity: Velocity) {
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        listener.onReturned(item.id)
        scope.launch {
            listOf(
                launch { focus.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) },
                launch { tilt.animateTo(0f, Motion.preciseLanding()) },
                launch { lift.animateTo(0f, Motion.preciseLanding()) },
                launch {
                    offset.animateTo(Offset.Zero, Motion.preciseLanding(), Offset(velocity.x, velocity.y))
                },
            ).joinAll()
            // Show the slot under the copy before removing the copy. A playing video moves back to the slot now, and
            // shows there (from the frame the copy shows) a frame before the copy goes.
            isAloft = false
            hiddenId = null
            awaitFrames(3)
            reset()
        }
    }

    private suspend fun reset() {
        trackJob?.cancel()
        item = null
        isAloft = false
        cardLayer = null
        offset.snapTo(Offset.Zero)
        lift.snapTo(0f)
        tilt.snapTo(0f)
        glow.snapTo(1f)
        flare.snapTo(0f)
    }

    /** Tells the listener about the lift once the overlay copy has been drawn, so its picture can go along. */
    private suspend fun announce(item: ShelfItemUi) {
        awaitFrames(2)
        if (this.item?.id != item.id) return
        val picture = cardLayer?.toImageBitmap()
        val thumbnail = picture?.let { withContext(Dispatchers.Default) { it.encodeWithinBudget() } }
        // Let go while the picture was being made: its return or send has already been reported, so a late lift
        // would open a slide on the peer that nothing ever closes.
        if (!dragging || this.item?.id != item.id) return
        listener.onLifted(item.id, restRect.width * LiftedScale, restRect.height * LiftedScale, thumbnail)
    }

    /**
     * One frame loop for the card's whole flight: a smoothed velocity leans the card into its motion while it is
     * held (righting itself when the finger rests) and, when [report] is set, every frame goes to the listener.
     */
    private suspend fun track(id: String, report: Boolean) {
        var last = offset.value
        var lastNanos = withFrameNanos { it }
        var velocity = Offset.Zero
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceAtLeast(0.001f)
            val current = offset.value
            velocity += ((current - last) / dt - velocity) * (1f - exp(-dt / VelocitySmoothingSeconds))
            last = current
            lastNanos = now
            if (dragging) {
                val thousandDps = velocity.x / density.density / 1000f
                tilt.snapTo((thousandDps * TiltDegreesPerThousandDps).coerceIn(-MaxTiltDegrees, MaxTiltDegrees))
            }
            val width = overlay?.size?.width?.toFloat() ?: continue
            if (report) {
                val card = currentCard()
                listener.onMoved(
                    SlideFrame(id, card.left, card.top, card.right, width, velocity.x, velocity.y, tilt.value, now),
                )
            }
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
internal fun rememberSlideState(listener: SlideListener, isMirrored: Boolean): SlideState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentListener by rememberUpdatedState(listener)
    val currentIsMirrored by rememberUpdatedState(isMirrored)
    return remember(scope, haptics, density) {
        val forwarding = object : SlideListener {
            override fun onLifted(id: String, cardWidthPx: Float, cardHeightPx: Float, thumbnail: ByteArray?) =
                currentListener.onLifted(id, cardWidthPx, cardHeightPx, thumbnail)

            override fun onMoved(frame: SlideFrame) = currentListener.onMoved(frame)
            override fun onReturned(id: String) = currentListener.onReturned(id)
            override fun onSent(id: String, edge: ExitEdge) = currentListener.onSent(id, edge)
        }
        SlideState(scope, haptics, density, forwarding, isMirrored = { currentIsMirrored })
    }
}
