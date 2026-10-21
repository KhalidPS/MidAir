package com.kotlistine.transferingfilebyair.feature.pairing.presentation.lineup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.awaitFrames
import com.kotlistine.transferingfilebyair.core.designsystem.motion.textSwap
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.core.presentation.ObserveAsEvents
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/** Within Android's 200 dp limit for keeping edge swipes from the back gesture. */
private val LaneHeight = 180.dp

/** A swipe must start or end this close to a side edge (about 6 mm) to count as crossing it. */
private val EdgeZone = 40.dp
private val MinSwipeSpeed = 150.dp
private const val TrailFadeNanos = 450_000_000L
private const val Unstamped = -1L

@Composable
fun LineUpRoot(onDone: () -> Unit, viewModel: LineUpViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            LineUpEvent.LinedUp -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    LineUpScreen(
        state = state,
        onAction = { action ->
            when (action) {
                LineUpAction.OnDoneClick, LineUpAction.OnSkipClick -> onDone()
                else -> viewModel.onAction(action)
            }
        },
    )
}

/**
 * One finger swipes through the glowing band from this phone straight onto the other: a light trail follows it, and
 * once both phones have felt it, the band lights up toward the other phone and the result shows.
 */
@Composable
fun LineUpScreen(state: LineUpState, onAction: (LineUpAction) -> Unit, modifier: Modifier = Modifier) {
    val trail = remember { SwipeTrail() }
    LaunchedEffect(trail) { trail.run() }

    // The band and the actions rise into place shortly after the screen appears; the text is already there.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        awaitFrames()
        delay(120)
        enter.animateTo(1f, Motion.slideIn())
    }
    // Once lined up, the band's end that faces the other phone lights up.
    val linedUp = remember { Animatable(0f) }
    LaunchedEffect(state.phase) {
        linedUp.animateTo(if (state.phase == LineUpPhase.LINED_UP) 1f else 0f, Motion.pop())
    }
    val flow = rememberInfiniteTransition(label = "lane")
    val flowPhase by flow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "lane flow",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AirColors.Background),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(LaneHeight)
                .keepEdgeSwipes()
                .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }
                .drawBehind {
                    drawLane(flowPhase, state.peerSide, linedUp.value)
                    trail.draw(this)
                }
                .pointerInput(Unit) {
                    val zone = EdgeZone.toPx()
                    val minSpeed = MinSwipeSpeed.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val samples = mutableListOf(TouchSample(down.position.x, down.position.y, down.uptimeMillis))
                        trail.add(down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            samples += TouchSample(change.position.x, change.position.y, change.uptimeMillis)
                            if (change.pressed) trail.add(change.position)
                            change.consume()
                            if (!change.pressed) break
                        }
                        edgeCrossing(samples, size.width.toFloat(), zone, minSpeed)
                            ?.let { onAction(LineUpAction.OnEdgeCrossed(it)) }
                    }
                },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            AnimatedContent(
                targetState = state.phase,
                transitionSpec = { textSwap() },
                label = "line-up headline",
            ) { phase ->
                Headline(phase, state)
            }
            Spacer(Modifier.weight(1f))
            Actions(
                phase = state.phase,
                onAction = onAction,
                modifier = Modifier.graphicsLayer {
                    alpha = enter.value.coerceIn(0f, 1f)
                    translationY = (1f - enter.value) * 56.dp.toPx()
                },
            )
        }
    }
}

@Composable
private fun Headline(phase: LineUpPhase, state: LineUpState) {
    val peer = state.peerName ?: "the other phone"
    val (title, body) = when (phase) {
        LineUpPhase.WAITING ->
            "Line up the screens" to
                "Hold the phones side by side. Swipe one finger through the band, from this screen straight onto $peer."
        LineUpPhase.MEASURING -> "Measuring…" to "Keep the phones where they are."
        LineUpPhase.MISSED ->
            "Not quite" to "The swipe has to run from one screen straight onto the other. Try again in one smooth move."
        LineUpPhase.LINED_UP -> "Lined up" to state.resultText(peer)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = AirColors.OnSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = AirColors.OnSurfaceMuted, textAlign = TextAlign.Center)
    }
}

/** "Khalid's Pixel is on your right, 11 mm away, its screen 4 mm lower." */
private fun LineUpState.resultText(peer: String): String {
    val side = if (peerSide == ExitEdge.LEFT) "left" else "right"
    val gap = gapMm?.roundToInt()
    val lower = peerLowerByMm?.roundToInt() ?: 0
    val height = when {
        abs(lower) < 2 -> ""
        lower > 0 -> ", its screen $lower mm lower"
        else -> ", its screen ${-lower} mm higher"
    }
    return "$peer is on your $side${if (gap != null) ", $gap mm away" else ""}$height. Swipe again to measure again."
}

@Composable
private fun Actions(phase: LineUpPhase, onAction: (LineUpAction) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (phase == LineUpPhase.LINED_UP) {
            Button(
                onClick = { onAction(LineUpAction.OnDoneClick) },
                colors = ButtonDefaults.buttonColors(containerColor = AirColors.OnSurface, contentColor = AirColors.Background),
            ) {
                Text("Done")
            }
        } else {
            TextButton(onClick = { onAction(LineUpAction.OnSkipClick) }) { Text("Skip") }
        }
    }
}

/**
 * The band to swipe through: a faint surface with prism-tinted rims, light drifting out toward both side edges
 * (where the finger should go), and, once lined up, a bloom at the end that faces the other phone.
 */
private fun DrawScope.drawLane(flowPhase: Float, peerSide: ExitEdge?, linedUp: Float) {
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.5f to AirColors.Surface.copy(alpha = 0.7f),
            1f to Color.Transparent,
        ),
    )
    val rim = Brush.horizontalGradient(AirColors.GlowSweep.map { it.copy(alpha = 0.35f) })
    val hairline = 1.dp.toPx()
    drawRect(rim, topLeft = Offset(0f, 0f), size = size.copy(height = hairline))
    drawRect(rim, topLeft = Offset(0f, size.height - hairline), size = size.copy(height = hairline))

    // Dots drifting from the middle out to both edges.
    val y = size.height / 2f
    val spacing = 28.dp.toPx()
    val count = (size.width / 2f / spacing).toInt() + 1
    for (i in 0 until count) {
        val t = ((i + flowPhase) * spacing) / (size.width / 2f)
        if (t > 1f) continue
        val alpha = 0.08f + 0.3f * t
        val radius = 1.5.dp.toPx() + 1.5.dp.toPx() * t
        drawCircle(AirColors.OnSurface.copy(alpha = alpha), radius, Offset(size.width / 2f + t * size.width / 2f, y))
        drawCircle(AirColors.OnSurface.copy(alpha = alpha), radius, Offset(size.width / 2f - t * size.width / 2f, y))
    }

    if (peerSide != null && linedUp > 0.01f) {
        val center = Offset(if (peerSide == ExitEdge.RIGHT) size.width else 0f, y)
        val radius = size.height * (0.6f + 0.6f * linedUp)
        drawCircle(
            Brush.radialGradient(
                0f to AirColors.BloomCore.copy(alpha = 0.8f),
                0.4f to AirColors.GlowCyan.copy(alpha = 0.35f),
                1f to Color.Transparent,
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            alpha = linedUp.coerceIn(0f, 1f),
            blendMode = BlendMode.Plus,
        )
    }
}

/** The light following the finger: recent points, fading out over a moment. Drawn only while it has points. */
@Stable
private class SwipeTrail {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val stamps = ArrayList<Long>()

    /** Advanced every frame while there is something to fade; read in the draw phase only. */
    private var frameNanos by mutableLongStateOf(0L)
    private var count by mutableIntStateOf(0)

    fun add(position: Offset) {
        xs += position.x
        ys += position.y
        stamps += Unstamped
        count = xs.size
    }

    suspend fun run() {
        while (true) {
            snapshotFlow { count }.first { it > 0 }
            while (xs.isNotEmpty()) {
                withFrameNanos { now ->
                    frameNanos = now
                    // Points get the time of the first frame that shows them.
                    for (i in stamps.indices.reversed()) {
                        if (stamps[i] != Unstamped) break
                        stamps[i] = now
                    }
                    var stale = 0
                    while (stale < stamps.size && now - stamps[stale] > TrailFadeNanos) stale++
                    if (stale > 0) {
                        xs.subList(0, stale).clear()
                        ys.subList(0, stale).clear()
                        stamps.subList(0, stale).clear()
                    }
                }
                count = xs.size
            }
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        val now = frameNanos
        for (i in 1 until xs.size) {
            if (stamps[i] == Unstamped) continue
            val life = 1f - ((now - stamps[i]).toFloat() / TrailFadeNanos).coerceIn(0f, 1f)
            if (life <= 0f) continue
            val from = Offset(xs[i - 1], ys[i - 1])
            val to = Offset(xs[i], ys[i])
            drawLine(AirColors.GlowCyan.copy(alpha = 0.3f * life), from, to, strokeWidth = 18.dp.toPx() * life, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(AirColors.BloomCore.copy(alpha = 0.9f * life), from, to, strokeWidth = 5.dp.toPx() * life, cap = StrokeCap.Round)
        }
    }
}

@Preview
@Composable
private fun LineUpWaitingPreview() {
    AirTheme { LineUpScreen(state = LineUpState(peerName = "Khalid's Pixel"), onAction = {}) }
}

@Preview
@Composable
private fun LineUpDonePreview() {
    AirTheme {
        LineUpScreen(
            state = LineUpState(
                phase = LineUpPhase.LINED_UP,
                peerName = "Khalid's Pixel",
                peerSide = ExitEdge.RIGHT,
                gapMm = 11f,
                peerLowerByMm = 4f,
            ),
            onAction = {},
        )
    }
}
