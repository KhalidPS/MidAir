package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.awaitFrames
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirDimens
import com.kotlistine.transferingfilebyair.core.presentation.image.decodeToImageBitmapOrNull
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.IncomingCardUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.roundToInt

/** How quickly the drawn card catches up with the latest reported position (matched by the sender-side lead). */
private const val FollowSmoothingSeconds = 0.03f

/** The 3D turn the card straightens out of as it springs to the middle, like the reference's laptop side. */
private const val ArrivalTurnDegrees = 24f
private const val HoldInBloomMillis = 200L
private val IncomingElevation = 28.dp

/** Every placed grid slot by item id, so a received card can find where it lands. */
internal class SlotRegistry {
    private val slots = HashMap<String, SlotCoordinates>()

    fun register(id: String, slot: SlotCoordinates) {
        slots[id] = slot
    }

    fun unregister(id: String, slot: SlotCoordinates) {
        if (slots[id] === slot) slots.remove(id)
    }

    operator fun get(id: String): LayoutCoordinates? = slots[id]?.value?.takeIf { it.isAttached }
}

/**
 * The receiving half of a slide: the peer's card follows its reports in from the facing edge (the shelf blurs and
 * the edge glows as it approaches), and once let go it springs to the middle with a 3D turn, rests in the light,
 * then shrinks into its new slot at the top of the shelf.
 */
@Stable
class IncomingSlideState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
) {
    var card: IncomingCardUi? by mutableStateOf(null)
        private set

    internal var picture: ImageBitmap? by mutableStateOf(null)
        private set

    /** Drawn only once a first position is known. */
    internal var isShown: Boolean by mutableStateOf(false)
        private set

    internal var entryEdge: ExitEdge by mutableStateOf(ExitEdge.LEFT)
        private set

    /** Card top-left in overlay pixels (before landing). */
    internal val position = Animatable(Offset.Zero, Offset.VectorConverter)
    internal val tilt = Animatable(0f)
    internal val turn = Animatable(0f)

    /** 0 → 1 while the card flies from where it rests into its slot. */
    internal val landing = Animatable(0f)

    /** How much of the card's file is here (0..1); shown as a ring while [isTransferring]. */
    internal val progress = Animatable(0f)

    /** Let go by the peer and waiting for its file: the ring shows, and a tap stops the transfer. */
    internal var isTransferring: Boolean by mutableStateOf(false)
        private set
    private val arrival = Animatable(0f)
    private val glow = Animatable(1f)

    /** Where the landing starts and, read every frame, where it ends (the slot moves while the shelf un-recedes). */
    private var landingFrom = Rect.Zero
    internal var landingTo: () -> Rect? = { null }
        private set
    private var lastLandingTo = Rect.Zero

    internal var containerSize = Size.Zero

    private var target = Offset.Zero
    private var targetTilt = 0f
    private var targetArrival = 0f
    private var followJob: Job? = null
    private var arrivalJob: Job? = null
    private var withdrawJob: Job? = null

    /** How far this screen's shelf recedes for the incoming card. Reads animated values. */
    fun focus(): Float = if (card == null) 0f else arrival.value * glow.value

    internal fun bloom(): Bloom? {
        val card = card ?: return null
        if (!isShown) return null
        val y = position.value.y + card.heightPx / 2f
        return Bloom(entryEdge, arrival.value * glow.value, y)
    }

    /** The drawn rect before scale and turn: following, resting, or part-way into its slot. Reads animated values. */
    internal fun drawnRect(card: IncomingCardUi): Rect {
        val resting = Rect(position.value, Size(card.widthPx, card.heightPx))
        val t = landing.value
        if (t == 0f) return resting
        landingTo()?.let { lastLandingTo = it }
        return lerp(landingFrom, lastLandingTo, t)
    }

    /**
     * A new card replaces whatever was still showing (a previous card mid-withdraw or mid-landing). Every job that
     * finishes a card checks it is still the card it started with, so the old one's ending never wipes the new one.
     */
    fun onLifted(card: IncomingCardUi) {
        followJob?.cancel()
        arrivalJob?.cancel()
        arrivalJob = null
        withdrawJob?.cancel()
        withdrawJob = null
        this.card = card
        picture = null
        isShown = false
        scope.launch {
            tilt.snapTo(0f)
            turn.snapTo(0f)
            landing.snapTo(0f)
            arrival.snapTo(0f)
            glow.snapTo(1f)
        }
        val bytes = card.thumbnail ?: return
        scope.launch {
            val decoded = withContext(Dispatchers.Default) { bytes.decodeToImageBitmapOrNull() }
            if (this@IncomingSlideState.card?.id == card.id) picture = decoded
        }
    }

    fun onMoved(id: String, leftPx: Float, topPx: Float, tiltDegrees: Float, arrival: Float, entryEdge: ExitEdge) {
        if (card?.id != id || arrivalJob != null) return
        target = Offset(leftPx, topPx)
        targetTilt = tiltDegrees
        targetArrival = arrival
        this.entryEdge = entryEdge
        if (!isShown) {
            isShown = true
            scope.launch { position.snapTo(target) }
        }
        if (followJob?.isActive != true) followJob = scope.launch { follow() }
    }

    /** The sender kept the card (or it couldn't be received): it slides back out the way it came. */
    fun onWithdrawn(id: String) {
        val card = card?.takeIf { it.id == id } ?: return
        followJob?.cancel()
        arrivalJob?.cancel()
        isTransferring = false
        withdrawJob = scope.launch {
            val outside = if (entryEdge == ExitEdge.LEFT) -card.widthPx * 1.2f else containerSize.width + card.widthPx * 0.2f
            listOf(
                launch { position.animateTo(Offset(outside, position.value.y), Motion.exit(260)) },
                launch { arrival.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) },
            ).joinAll()
            clear(card.id)
        }
    }

    /** How much of the arriving card's file is here. */
    fun onProgress(id: String, fraction: Float) {
        if (card?.id != id) return
        scope.launch { progress.animateTo(fraction.coerceIn(0f, 1f), tween(180, easing = FastOutSlowInEasing)) }
    }

    /** The sender let go past the edge: the card springs to the middle with a 3D turn and waits for its slot. */
    fun onArrived(id: String) {
        val card = card?.takeIf { it.id == id } ?: return
        followJob?.cancel()
        isTransferring = true
        scope.launch { progress.snapTo(0f) }
        arrivalJob = scope.launch {
            if (!isShown) {
                // Nothing streamed (a fast flick, or moves lost): start just outside the facing edge.
                val outside = if (entryEdge == ExitEdge.LEFT) -card.widthPx else containerSize.width
                position.snapTo(Offset(outside, (containerSize.height - card.heightPx) / 2f))
                isShown = true
            }
            val centre = Offset((containerSize.width - card.widthPx) / 2f, (containerSize.height - card.heightPx) / 2f)
            // Turned toward the edge it came through, straightening as it settles.
            turn.snapTo(if (entryEdge == ExitEdge.LEFT) -ArrivalTurnDegrees else ArrivalTurnDegrees)
            listOf(
                launch { position.animateTo(centre, spring(dampingRatio = 0.62f, stiffness = 170f)) },
                launch { turn.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 150f)) },
                launch { tilt.animateTo(0f, Motion.preciseLanding()) },
                launch { arrival.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) },
            ).joinAll()
        }
    }

    /**
     * Lands the arrived card in its slot ([slot] is read every frame and is null until the slot is laid out), then
     * returns the id of the card it landed (null if there was none). The caller then calls [handOff] with it, also
     * when the landing is cut short (a newer card took over), so the slot is always shown.
     */
    suspend fun land(slot: () -> Rect?): String? {
        val card = card ?: return null
        arrivalJob?.join()
        delay(HoldInBloomMillis)
        // Wait (briefly) for the grid to lay the new item out.
        var destination: Rect? = null
        for (frame in 0 until 60) {
            if (this.card?.id != card.id) return card.id
            destination = slot()
            if (destination != null) break
            awaitFrames(1)
        }
        if (this.card?.id != card.id) return card.id
        if (destination == null) {
            // No slot to land in: let the card fade into the light instead.
            glow.animateTo(0f, tween(400, easing = FastOutLinearInEasing))
            return card.id
        }
        // Every byte is here: the ring closes and gives way to the landing.
        progress.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
        isTransferring = false
        landingFrom = Rect(position.value, Size(card.widthPx, card.heightPx))
        lastLandingTo = destination
        landingTo = slot
        coroutineScope {
            launch { landing.animateTo(1f, Motion.preciseLanding()) }
            launch { glow.animateTo(0f, tween(500, easing = FastOutSlowInEasing)) }
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        return card.id
    }

    /**
     * Hands the landed card ([cardId]) over to its slot: [showSlot] makes the slot visible, and the card is removed
     * once the slot has drawn, so there is no empty frame. Runs to the end even if the caller is cancelled meanwhile
     * (showing the slot usually is what cancels it), so the card is never left behind; a newer card is left alone.
     */
    suspend fun handOff(cardId: String?, showSlot: () -> Unit) = withContext(NonCancellable) {
        showSlot()
        awaitFrames(2)
        if (cardId != null) clear(cardId)
    }

    /** Removes the card, unless a newer card has already replaced the one with [id]. */
    private suspend fun clear(id: String) {
        if (card?.id != id) return
        followJob?.cancel()
        arrivalJob = null
        isTransferring = false
        card = null
        picture = null
        isShown = false
        landingTo = { null }
        landing.snapTo(0f)
        arrival.snapTo(0f)
        glow.snapTo(1f)
    }

    /** Eases the drawn card toward the latest report every frame, hiding the uneven arrival of messages. */
    private suspend fun follow() {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceAtLeast(0.001f)
            lastNanos = now
            val k = 1f - exp(-dt / FollowSmoothingSeconds)
            position.snapTo(position.value + (target - position.value) * k)
            tilt.snapTo(tilt.value + (targetTilt - tilt.value) * k)
            arrival.snapTo(arrival.value + (targetArrival - arrival.value) * k)
        }
    }
}

@Composable
internal fun rememberIncomingSlideState(): IncomingSlideState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    return remember(scope, haptics) { IncomingSlideState(scope, haptics) }
}

/**
 * The peer's card on this screen: its picture at the sender's physical size, moved only by its layer. While its file
 * comes in, a ring shows how much has arrived, and [onTapWhileTransferring] lets the user stop it.
 */
@Composable
internal fun IncomingCard(
    state: IncomingSlideState,
    onTapWhileTransferring: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.card ?: return
    val picture = state.picture
    Box(
        modifier = modifier
            .layout { measurable, _ ->
                val width = card.widthPx.roundToInt()
                val height = card.heightPx.roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                if (!state.isShown) {
                    alpha = 0f
                    return@graphicsLayer
                }
                val rect = state.drawnRect(card)
                // Scale about the centre: place the unscaled card so its centre sits on the drawn rect's centre.
                translationX = rect.center.x - card.widthPx / 2f
                translationY = rect.center.y - card.heightPx / 2f
                scaleX = rect.width / card.widthPx
                scaleY = rect.height / card.heightPx
                rotationZ = state.tilt.value
                rotationY = state.turn.value
                cameraDistance = 12f * density
                shadowElevation = IncomingElevation.toPx() * (1f - state.landing.value)
                shape = RoundedCornerShape(AirDimens.CardCorner)
                clip = true
            }
            .clickable(
                enabled = state.isTransferring,
                interactionSource = null,
                indication = null,
                onClickLabel = "Stop receiving",
            ) { onTapWhileTransferring(card.id) }
            .drawWithContent {
                drawContent()
                if (state.isTransferring) drawProgressRing(state.progress.value)
            }
            .background(AirColors.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(bitmap = picture, contentDescription = card.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = card.name,
                style = MaterialTheme.typography.labelLarge,
                color = AirColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** A dimmed card with a ring filling clockwise from the top: how much of the file has arrived. */
private fun DrawScope.drawProgressRing(progress: Float) {
    drawRect(AirColors.Background.copy(alpha = 0.35f))
    val radius = size.minDimension * 0.18f
    val stroke = 4.dp.toPx()
    val topLeft = Offset(center.x - radius, center.y - radius)
    val ringSize = Size(radius * 2, radius * 2)
    drawCircle(AirColors.OnSurface.copy(alpha = 0.25f), radius = radius, center = center, style = Stroke(stroke))
    drawArc(
        color = AirColors.OnSurface,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        topLeft = topLeft,
        size = ringSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}
