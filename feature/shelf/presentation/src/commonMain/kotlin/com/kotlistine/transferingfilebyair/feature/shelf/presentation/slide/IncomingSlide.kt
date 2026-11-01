package com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
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
import com.kotlistine.transferingfilebyair.core.media.VideoSurface
import com.kotlistine.transferingfilebyair.core.presentation.image.decodeToImageBitmapOrNull
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.LocalCardVideo
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.IncomingCardUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** How the drawn card's speed is measured, to carry it into the landing. */
private const val DrawnVelocitySmoothingSeconds = 0.03f

/** A let-go faster than this (pixels per second) is carried into the landing at this speed. */
private const val MaxCarriedSpeedPx = 6000f

/** How long the light takes to ripple out across the screen after a card arrives. */
private const val WaveMillis = 1100
private val IncomingElevation = 28.dp

/** Every placed grid slot by key, so a received card can find where it lands. */
internal class SlotRegistry {
    private val slots = HashMap<String, SlotCoordinates>()

    fun register(key: String, slot: SlotCoordinates) {
        slots[key] = slot
    }

    fun unregister(key: String, slot: SlotCoordinates) {
        if (slots[key] === slot) slots.remove(key)
    }

    operator fun get(key: String): LayoutCoordinates? = slots[key]?.value?.takeIf { it.isAttached }
}

/** Light rippling out from where a card came in: [progress] runs 0 → 1, then it is gone. */
internal class Wave(val edge: ExitEdge, val centerY: Float, val progress: Float)

/**
 * The receiving half of a slide: the peer's card follows its reports in from the facing edge (the shelf blurs and
 * the edge glows as it approaches). Once let go, the light ripples out across the screen and the card flies straight
 * into its slot at the top of the shelf, where it waits for its file. Several cards can wait at once; this state only
 * draws the one in the air.
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

    /** Pictures of the cards that came in, by card id, for their slots on the shelf. */
    internal val pictures = mutableStateMapOf<String, ImageBitmap>()

    /** The card flying into its slot: the slot stays empty until it lands. */
    internal var flyingId: String? by mutableStateOf(null)
        private set

    /** Drawn only once a first position is known. */
    internal var isShown: Boolean by mutableStateOf(false)
        private set

    internal var entryEdge: ExitEdge by mutableStateOf(ExitEdge.LEFT)
        private set

    /** Where a card's slot is (overlay pixels), by card id; null until it is laid out. Set by the screen. */
    internal var slotOf: (id: String) -> Rect? = { null }

    /** Card top-left in overlay pixels (before landing). */
    internal val position = Animatable(Offset.Zero, Offset.VectorConverter)
    internal val tilt = Animatable(0f)
    internal val turn = Animatable(0f)

    /** 0 → 1 while the card flies from where it was let go into its slot. */
    internal val landing = Animatable(0f)

    /** The let-go's momentum, on top of the landing: starts at the card's speed and dies out by the slot. */
    private val momentum = Animatable(Offset.Zero, Offset.VectorConverter)
    private val arrival = Animatable(0f)
    private val glow = Animatable(1f)
    private val wave = Animatable(1f)
    private var waveEdge = ExitEdge.LEFT
    private var waveCenterY = 0f

    /** Where the landing starts and, read every frame, where it ends (the slot moves while the shelf un-recedes). */
    private var landingFrom = Rect.Zero
    private var landingTo: () -> Rect? = { null }
    private var lastLandingTo = Rect.Zero

    internal var containerSize = Size.Zero

    /** The peer's moves, played back evenly. */
    private val playback = MovePlayback()
    private val clockOrigin = TimeSource.Monotonic.markNow()

    /** Frame times on this phone's clock: frames are timed by another clock, this far from it. */
    private var frameToClockMillis = Double.POSITIVE_INFINITY

    /** How fast the card moves as drawn, once it has been drawn on [followedFrames] frames. */
    private var drawnVelocity = Offset.Zero
    private var followedFrames = 0
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

    internal fun wave(): Wave? {
        val progress = wave.value
        return if (progress < 1f) Wave(waveEdge, waveCenterY, progress) else null
    }

    /** The drawn rect before scale and turn: following, or part-way into its slot. Reads animated values. */
    internal fun drawnRect(card: IncomingCardUi): Rect {
        val following = Rect(position.value, Size(card.widthPx, card.heightPx))
        val t = landing.value
        if (t == 0f) return following.translate(momentum.value)
        landingTo()?.let { lastLandingTo = it }
        return lerp(landingFrom, lastLandingTo, t).translate(momentum.value)
    }

    /**
     * A new card replaces whatever was still showing (a previous card mid-withdraw or mid-landing; a landing cut
     * short still hands its slot over). Every job that finishes a card checks it is still the card it started with,
     * so the old one's ending never wipes the new one.
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
        playback.start()
        drawnVelocity = Offset.Zero
        followedFrames = 0
        scope.launch {
            tilt.snapTo(0f)
            turn.snapTo(0f)
            landing.snapTo(0f)
            arrival.snapTo(0f)
            glow.snapTo(1f)
        }
        val bytes = card.thumbnail ?: return
        scope.launch {
            val decoded = withContext(Dispatchers.Default) { bytes.decodeToImageBitmapOrNull() } ?: return@launch
            pictures[card.id] = decoded
            if (this@IncomingSlideState.card?.id == card.id) picture = decoded
        }
    }

    fun onMoved(
        id: String,
        leftPx: Float,
        topPx: Float,
        velocityXPxPerS: Float,
        velocityYPxPerS: Float,
        tiltDegrees: Float,
        arrival: Float,
        entryEdge: ExitEdge,
        sentAtMillis: Long,
        leadMillis: Float,
    ) {
        if (card?.id != id || arrivalJob != null) return
        val receivedAt = clockMillis()
        playback.add(
            MoveSample(
                // A peer that doesn't say when is played back by arrival, as before.
                sentAtMillis = if (sentAtMillis > 0L) sentAtMillis.toDouble() else receivedAt,
                receivedAtMillis = receivedAt,
                x = leftPx,
                y = topPx,
                vx = velocityXPxPerS,
                vy = velocityYPxPerS,
                tilt = tiltDegrees,
                arrival = arrival,
            ),
            leadMillis = leadMillis.toDouble(),
        )
        this.entryEdge = entryEdge
        if (!isShown) {
            isShown = true
            scope.launch { position.snapTo(Offset(leftPx, topPx)) }
        }
        if (followJob?.isActive != true) followJob = scope.launch { follow() }
    }

    /**
     * The sender kept the card: it slides back out the way it came. A card that already arrived stays: its slot on
     * the shelf goes away instead if its file doesn't come.
     */
    fun onWithdrawn(id: String) {
        val card = card?.takeIf { it.id == id } ?: return
        if (arrivalJob != null) return
        followJob?.cancel()
        withdrawJob = scope.launch {
            val outside = if (entryEdge == ExitEdge.LEFT) -card.widthPx * 1.2f else containerSize.width + card.widthPx * 0.2f
            listOf(
                launch { position.animateTo(Offset(outside, position.value.y), Motion.exit(260)) },
                launch { arrival.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) },
            ).joinAll()
            clear(card.id)
        }
    }

    /**
     * The sender let go past the edge: the light ripples out from where the card came in, and the card flies
     * straight into its slot ([slotOf]), straightening as it goes, then the slot takes over from it. It carries on
     * at the speed it was drawn moving (or, if it never was, the speed it was let go at, [velocity]), so the flight
     * across the edge is one motion.
     */
    fun onArrived(id: String, velocity: Offset = Offset.Zero) {
        val card = card?.takeIf { it.id == id } ?: return
        followJob?.cancel()
        val carried = (if (followedFrames >= 2) drawnVelocity else velocity).limitedTo(MaxCarriedSpeedPx)
        flyingId = id
        arrivalJob = scope.launch {
            try {
                if (!isShown) {
                    // Nothing streamed (a fast flick, a retry, or moves lost): start just outside the facing edge.
                    val outside = if (entryEdge == ExitEdge.LEFT) -card.widthPx else containerSize.width
                    position.snapTo(Offset(outside, (containerSize.height - card.heightPx) / 2f))
                    isShown = true
                }
                // Not tied to this card: the next one may already be on its way while the light spreads.
                val edge = entryEdge
                val centerY = position.value.y + card.heightPx / 2f
                scope.launch { ripple(edge, centerY) }
                flyIntoSlot(card, carried)
            } finally {
                // Even when cut short, the slot shows first and the card goes a couple of frames later: no gap.
                withContext(NonCancellable) {
                    if (flyingId == card.id) flyingId = null
                    awaitFrames(2)
                    clear(card.id)
                }
            }
        }
    }

    private suspend fun flyIntoSlot(card: IncomingCardUi, carried: Offset) {
        // Wait (briefly) for the grid to lay the new slot out.
        var destination: Rect? = null
        for (frame in 0 until 60) {
            destination = slotOf(card.id)
            if (destination != null) break
            awaitFrames(1)
        }
        if (destination == null) {
            // No slot to land in (scrolled away, or already gone): the card fades into the light instead.
            glow.animateTo(0f, tween(400, easing = FastOutLinearInEasing))
            return
        }
        landingFrom = Rect(position.value, Size(card.widthPx, card.heightPx))
        lastLandingTo = destination
        landingTo = { slotOf(card.id) }
        momentum.snapTo(Offset.Zero)
        coroutineScope {
            launch { landing.animateTo(1f, Motion.preciseLanding()) }
            launch { momentum.animateTo(Offset.Zero, Motion.preciseLanding(), initialVelocity = carried) }
            launch { turn.animateTo(0f, Motion.preciseLanding()) }
            launch { tilt.animateTo(0f, Motion.preciseLanding()) }
            launch { arrival.animateTo(1f, tween(120)) }
            launch { glow.animateTo(0f, tween(520, easing = FastOutSlowInEasing)) }
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    private suspend fun ripple(edge: ExitEdge, centerY: Float) {
        waveEdge = edge
        waveCenterY = centerY
        wave.snapTo(0f)
        wave.animateTo(1f, tween(WaveMillis, easing = LinearEasing))
    }

    /** Removes the card, unless a newer card has already replaced the one with [id]. */
    private suspend fun clear(id: String) {
        if (card?.id != id) return
        followJob?.cancel()
        arrivalJob = null
        card = null
        picture = null
        isShown = false
        landingTo = { null }
        drawnVelocity = Offset.Zero
        followedFrames = 0
        momentum.snapTo(Offset.Zero)
        landing.snapTo(0f)
        arrival.snapTo(0f)
        glow.snapTo(1f)
    }

    /** Draws the card where [playback] has it on every frame, and measures how fast it moves as drawn. */
    private suspend fun follow() {
        var lastMillis = Double.NaN
        var last = position.value
        while (true) {
            val now = frameMillis(withFrameNanos { it })
            val pose = playback.poseAt(now) ?: continue
            val drawn = Offset(pose.x, pose.y)
            if (!lastMillis.isNaN()) {
                val dt = ((now - lastMillis) / 1000.0).toFloat().coerceAtLeast(0.001f)
                val k = 1f - exp(-dt / DrawnVelocitySmoothingSeconds)
                drawnVelocity += ((drawn - last) / dt - drawnVelocity) * k
                followedFrames++
            }
            lastMillis = now
            last = drawn
            position.snapTo(drawn)
            tilt.snapTo(pose.tilt)
            arrival.snapTo(pose.arrival)
        }
    }

    private fun clockMillis(): Double = clockOrigin.elapsedNow().inWholeMicroseconds / 1000.0

    /**
     * [frameNanos] on [clockMillis]'s clock. Frame code runs a little after its frame time, never before, so the
     * smallest difference seen between the two clocks is how far apart they are.
     */
    private fun frameMillis(frameNanos: Long): Double {
        val frame = frameNanos / 1_000_000.0
        val apart = clockMillis() - frame
        if (apart < frameToClockMillis) frameToClockMillis = apart
        return frame + frameToClockMillis
    }
}

@Composable
internal fun rememberIncomingSlideState(): IncomingSlideState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    return remember(scope, haptics) { IncomingSlideState(scope, haptics) }
}

/**
 * The peer's card on this screen: its picture at the sender's physical size, moved only by its layer. Place it at
 * the overlay's absolute top-left (also in right-to-left layouts): its layer offsets are in overlay pixels.
 *
 * When the card is the peer's playing video, this half plays it too, on the same frame (the picture shows until the
 * first frame arrives).
 */
@Composable
internal fun IncomingCard(state: IncomingSlideState, modifier: Modifier = Modifier) {
    val card = state.card ?: return
    val picture = state.picture
    val video = LocalCardVideo.current?.takeIf { it.itemId == card.id }
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
            .background(AirColors.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (video != null) {
            VideoSurface(
                engine = video.engine,
                isActive = true,
                modifier = Modifier.fillMaxSize(),
                placeholder = { CardPicture(picture, card.name) },
            )
        } else {
            CardPicture(picture, card.name)
        }
    }
}

/** The card as the sender drew it, or its name when there is no picture. */
@Composable
private fun CardPicture(picture: ImageBitmap?, name: String) {
    if (picture != null) {
        Image(bitmap = picture, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CardName(name) }
    }
}

/** What a card without a picture shows: its file name. */
@Composable
internal fun CardName(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        style = MaterialTheme.typography.labelLarge,
        color = AirColors.OnSurface,
        maxLines = 2,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = modifier.padding(12.dp),
    )
}

/** The same direction, no faster than [maxSpeed]. */
private fun Offset.limitedTo(maxSpeed: Float): Offset {
    val speed = getDistance()
    return if (speed > maxSpeed) this * (maxSpeed / speed) else this
}
