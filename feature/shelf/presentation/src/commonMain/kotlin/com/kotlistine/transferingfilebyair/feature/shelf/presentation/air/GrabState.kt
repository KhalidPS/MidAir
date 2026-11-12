package com.kotlistine.transferingfilebyair.feature.shelf.presentation.air

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.awaitFrames
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirDimens
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.FilePreview
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.HandPointUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.encodeWithinBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** How small the card gets inside the fist, before it is only light. */
private const val HeldScale = 0.14f
private const val BreathMillis = 900
private val GrabbedElevation = 24.dp
private val HeldLightRadius = 72.dp

/**
 * How tightly the cursor and the held light follow the (already smoothed) hand: settles in about 0.1 s, a glide
 * between camera pictures rather than a trail behind the hand.
 */
private const val HandFollowStiffness = 1_400f

/** What the grab tells the screen. All sizes are in pixels. */
fun interface GrabListener {
    /** [id]'s card is in the hand. [thumbnail] is the card as drawn, compressed; null when it couldn't be captured. */
    fun onGrabbed(id: String, cardWidthPx: Float, cardHeightPx: Float, thumbnail: ByteArray?)
}

/**
 * The grabbing half of an air gesture: the card nearest the fist is squeezed out of its slot into the fist and turns
 * into a ball of light that breathes while it is held and follows the hand. When the hand opens in front of the
 * other phone the light flares and goes out here; when it opens back here (or nobody takes it) the card un-squeezes
 * into its slot. Every animated value is read in the draw phase.
 */
@Stable
class GrabState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val listener: GrabListener,
) {
    /** The card in the hand (or on its way in or out of it). Non-null until its animation fully ends. */
    var item: ShelfItemUi? by mutableStateOf(null)
        private set

    /** The grid slot whose preview is hidden while its card is in the hand. */
    var hiddenId: String? by mutableStateOf(null)
        private set

    /** The card is in the hand: a playing video shows in the grabbed copy, not in its slot. */
    internal var isAloft by mutableStateOf(false)
        private set

    /** Where the card was grabbed from, in overlay coordinates. */
    internal var restRect: Rect by mutableStateOf(Rect.Zero)
        private set

    /** Where the fist is, in overlay coordinates. */
    internal val fist = Animatable(Offset.Zero, Offset.VectorConverter)

    /** 0 = the card in its slot, 1 = squeezed into the fist. */
    internal val squeeze = Animatable(0f)

    /** The held light: 0 = none, 1 = full; above 1 while it flares as the other phone takes it. */
    internal val light = Animatable(0f)

    /** The card's recording, set by [GrabbedCard]; captured once for the peer. */
    internal var cardLayer: GraphicsLayer? = null

    /** The overlay's size, set by the screen. */
    internal var containerSize = Size.Zero

    /** The card nearest to a point (overlay pixels) and its slot rect, if any can be grabbed. Set by the screen. */
    internal var pick: (Offset) -> Pair<ShelfItemUi, Rect>? = { null }

    private var job: Job? = null

    /**
     * A fist closed at ([x], [y]) (0..1 across and down the screen): grabs the card nearest to it. Nothing happens
     * while another card is in a hand, or when there's no card to grab.
     */
    fun grabAt(x: Float, y: Float) {
        if (item != null) return
        val point = Offset(x * containerSize.width, y * containerSize.height)
        val (picked, rect) = pick(point) ?: return
        item = picked
        restRect = rect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        job = scope.launch {
            fist.snapTo(point)
            squeeze.snapTo(0f)
            light.snapTo(0f)
            // The copy is drawn over the slot first (and recorded); the slot hides only once it has presented.
            awaitFrames(2)
            if (item?.id != picked.id) return@launch
            hiddenId = picked.id
            isAloft = true
            val picture = cardLayer?.toImageBitmap()
            // The squeeze starts now; the picture for the peer is compressed meanwhile. Waiting for it first made the
            // grab lag behind the fist by a good part of a second.
            launch {
                val thumbnail = picture?.let { withContext(Dispatchers.Default) { it.encodeWithinBudget() } }
                if (item?.id == picked.id) listener.onGrabbed(picked.id, rect.width, rect.height, thumbnail)
            }
            launch { squeeze.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f)) }
            light.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
            // Breathes while held, so it reads as alive and waiting.
            while (true) {
                light.animateTo(0.72f, tween(BreathMillis, easing = FastOutSlowInEasing))
                light.animateTo(1f, tween(BreathMillis, easing = FastOutSlowInEasing))
            }
        }
    }

    /** The hand moved (0..1 across and down the screen; null when it left the camera's view): the light follows. */
    fun onHand(hand: HandPointUi?) {
        if (item == null || hand == null) return
        val target = Offset(hand.x * containerSize.width, hand.y * containerSize.height)
        // Stiff: the hand position is already smoothed, so the light only glides between camera pictures.
        scope.launch { fist.animateTo(target, spring(dampingRatio = 1f, stiffness = HandFollowStiffness)) }
    }

    /** The card goes back into its slot. */
    fun putBack(id: String) {
        if (item?.id != id) return
        job?.cancel()
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        job = scope.launch {
            listOf(
                launch { squeeze.animateTo(0f, Motion.preciseLanding()) },
                launch { light.animateTo(0f, tween(260, easing = FastOutSlowInEasing)) },
            ).joinAll()
            // Show the slot under the copy (with its video back) before removing the copy.
            isAloft = false
            hiddenId = null
            awaitFrames(2)
            reset()
        }
    }

    /** The other phone took the card: the light flares and goes out, and the shelf closes the gap. */
    fun caught(id: String) {
        if (item?.id != id) return
        job?.cancel()
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        job = scope.launch {
            launch { squeeze.animateTo(1f, tween(160)) }
            light.animateTo(1.6f, tween(140, easing = FastOutSlowInEasing))
            light.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
            reset()
            // The slot stays hidden while the grid fades the sent item out; shown again in case it comes back.
            delay(400)
            if (item == null) hiddenId = null
        }
    }

    /** How far the shelf recedes while a card is in the hand. Reads animated values. */
    fun focus(): Float = if (item == null) 0f else 0.5f * light.value.coerceIn(0f, 1f) * squeeze.value.coerceIn(0f, 1f)

    /** The held light, in overlay coordinates. Reads animated values: call it from the draw phase. */
    internal fun heldLight(): HeldLight? {
        if (item == null) return null
        val intensity = light.value * squeeze.value.coerceIn(0f, 1f)
        return if (intensity > 0.005f) HeldLight(fist.value, intensity) else null
    }

    private suspend fun reset() {
        item = null
        isAloft = false
        cardLayer = null
        squeeze.snapTo(0f)
        light.snapTo(0f)
    }
}

internal class HeldLight(val center: Offset, val intensity: Float)

@Composable
internal fun rememberGrabState(listener: GrabListener = GrabListener { _, _, _, _ -> }): GrabState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentListener by rememberUpdatedState(listener)
    return remember(scope, haptics) {
        GrabState(scope, haptics) { id, width, height, thumbnail -> currentListener.onGrabbed(id, width, height, thumbnail) }
    }
}

/**
 * The grabbed card on its way into (or out of) the fist. Laid out once at the slot's pixel size; only its layer
 * moves. Place it at the overlay's absolute top-left: its offsets are in overlay pixels.
 */
@Composable
internal fun GrabbedCard(state: GrabState, modifier: Modifier = Modifier) {
    val item = state.item ?: return
    val rest = state.restRect
    // Recorded as it draws (untransformed), so the grab can send a picture of it.
    val recording = rememberGraphicsLayer()
    SideEffect { state.cardLayer = recording }
    FilePreview(
        item = item,
        // A playing video keeps playing in the fist (it shrinks into the light), and goes back to its slot with it.
        isVideoActive = state.isAloft,
        modifier = modifier
            .layout { measurable, _ ->
                val width = rest.width.roundToInt()
                val height = rest.height.roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                val squeeze = state.squeeze.value
                val center = lerp(rest.center, state.fist.value, squeeze.coerceIn(0f, 1f))
                val scale = lerp(1f, HeldScale, squeeze).coerceAtLeast(HeldScale * 0.5f)
                translationX = center.x - rest.width / 2f
                translationY = center.y - rest.height / 2f
                scaleX = scale
                scaleY = scale
                // Gone into the light by the time it is fist-sized.
                alpha = (1f - (squeeze - 0.55f) / 0.4f).coerceIn(0f, 1f)
                shadowElevation = GrabbedElevation.toPx() * (1f - squeeze).coerceIn(0f, 1f)
                shape = RoundedCornerShape(AirDimens.CardCorner)
                clip = true
            }
            .drawWithContent {
                recording.record { this@drawWithContent.drawContent() }
                drawLayer(recording)
            },
    )
}

/** The card held in the fist, as light. Draw it over the shelf, full screen. */
@Composable
internal fun HeldCardLight(state: GrabState, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                val held = state.heldLight() ?: return@drawBehind
                val alpha = held.intensity.coerceAtMost(1f)
                val radius = HeldLightRadius.toPx() * (0.7f + 0.3f * held.intensity)
                val core = AirColors.BloomCore
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to core.copy(alpha = 0.95f * alpha),
                        0.25f to core.copy(alpha = 0.6f * alpha),
                        0.55f to AirColors.GlowMagenta.copy(alpha = 0.22f * alpha),
                        1f to Color.Transparent,
                        center = held.center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = held.center,
                )
            },
    )
}

/**
 * Where the camera sees the hand: a faint ring that tightens into a dot as the hand closes, so the user knows the
 * phone is watching and roughly where it thinks the hand is. [hand] is read in the draw phase only.
 */
@Composable
internal fun HandCursor(hand: () -> HandPointUi?, modifier: Modifier = Modifier) {
    // Glides between camera pictures (0..1 across and down) instead of jumping at camera rate.
    val position = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var isFist by remember { mutableStateOf(false) }
    var isShown by remember { mutableStateOf(false) }
    val currentHand by rememberUpdatedState(hand)
    LaunchedEffect(position) {
        snapshotFlow { currentHand() }.collectLatest { point ->
            if (point == null) {
                isShown = false
                return@collectLatest
            }
            isFist = point.isFist
            val target = Offset(point.x, point.y)
            if (!isShown) {
                // Appears where the hand is, rather than flying in from where it was last seen.
                position.snapTo(target)
                isShown = true
            } else {
                position.animateTo(target, spring(dampingRatio = 1f, stiffness = HandFollowStiffness))
            }
        }
    }
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                if (!isShown) return@drawBehind
                val point = position.value
                val center = Offset(point.x * size.width, point.y * size.height)
                if (isFist) {
                    drawCircle(AirColors.OnSurface.copy(alpha = 0.35f), radius = 10.dp.toPx(), center = center)
                } else {
                    drawCircle(
                        color = AirColors.OnSurface.copy(alpha = 0.28f),
                        radius = 30.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
    )
}
