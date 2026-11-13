package com.kotlistine.transferingfilebyair.feature.pairing.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlistine.transferingfilebyair.core.designsystem.effects.EdgeGlow
import com.kotlistine.transferingfilebyair.core.designsystem.motion.LocalReducedMotion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.awaitFrames
import com.kotlistine.transferingfilebyair.core.designsystem.motion.textSwap
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.core.domain.util.DataError
import com.kotlistine.transferingfilebyair.core.presentation.ObserveAsEvents
import com.kotlistine.transferingfilebyair.feature.pairing.presentation.components.HoldToConnectButton
import com.kotlistine.transferingfilebyair.feature.pairing.presentation.components.PairingMotion
import com.kotlistine.transferingfilebyair.feature.pairing.presentation.components.rememberPairingMotion
import com.kotlistine.transferingfilebyair.feature.pairing.presentation.lineup.LineUpRoot
import com.kotlistine.transferingfilebyair.feature.pairing.presentation.permission.rememberNearbyPermissions
import com.kotlistine.transferingfilebyair.feature.pairing.presentation.scan.QrScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.hypot
import kotlin.math.max

@Composable
fun PairingRoot(onDone: () -> Unit, viewModel: PairingViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val motion = rememberPairingMotion()
    val scope = rememberCoroutineScope()
    // The permission answer arrives through a callback created before `permissions` exists, so it checks Location
    // through this holder.
    val isLocationOn = remember { arrayOf({ true }) }
    val permissions = rememberNearbyPermissions { granted ->
        viewModel.onAction(PairingAction.OnPermissionsResult(granted, locationOn = isLocationOn[0]()))
    }
    isLocationOn[0] = permissions::isLocationOn
    // Right after pairing, and on request, the screens are lined up before going back to the shelf.
    var isLiningUp by rememberSaveable { mutableStateOf(false) }
    // Leaving happens once: the pairing burst ending and a tap on Done can both ask for it.
    var hasLeft by remember { mutableStateOf(false) }
    fun leave() {
        if (hasLeft) return
        hasLeft = true
        onDone()
    }

    // Also after coming back from the system settings, where the user may have just allowed it or turned Location on.
    LifecycleResumeEffect(permissions) {
        if (permissions.isGranted()) {
            viewModel.onAction(PairingAction.OnPermissionsResult(granted = true, locationOn = permissions.isLocationOn()))
        }
        onPauseOrDispose { }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            // Both devices light up together, then line the screens up (phones) or go back to the shelf (a laptop,
            // which places the phone itself).
            is PairingEvent.Paired -> scope.launch {
                motion.burst()
                if (event.linesUp) isLiningUp = true else leave()
            }
        }
    }

    AnimatedContent(
        targetState = isLiningUp && state.phase == PairingPhase.CONNECTED,
        transitionSpec = { fadeIn(tween(320, delayMillis = 90)) togetherWith fadeOut(tween(160)) },
        label = "pairing step",
    ) { liningUp ->
        if (liningUp) {
            LineUpRoot(onDone = ::leave)
        } else {
            PairingScreen(
                state = state,
                motion = motion,
                onAction = { action ->
                    when (action) {
                        PairingAction.OnAllowClick -> when (state.error) {
                            DataError.Network.LOCATION_OFF -> permissions.openLocationSettings()
                            DataError.Network.PERMISSION_MISSING -> permissions.openSettings()
                            else -> permissions.request()
                        }
                        PairingAction.OnCloseClick -> leave()
                        PairingAction.OnLineUpClick -> isLiningUp = true
                        else -> viewModel.onAction(action)
                    }
                },
            )
        }
    }
    // Over everything while open: the camera, looking for the laptop's code.
    if (state.isScanning) {
        QrScanner(
            onScanned = { text -> viewModel.onAction(PairingAction.OnCodeScanned(text)) },
            onCancel = { viewModel.onAction(PairingAction.OnScanCancel) },
        )
    }
}

@Composable
fun PairingScreen(
    state: PairingState,
    onAction: (PairingAction) -> Unit,
    modifier: Modifier = Modifier,
    motion: PairingMotion = rememberPairingMotion(),
) {
    // The glow sits low while waiting, wakes when the other phone is found, and charges while Connect is held.
    val restingGlow by animateFloatAsState(state.phase.glow(), Motion.slideIn(), label = "glow")
    // With less motion asked for, the glow stays lit but stands still.
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(motion, reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var last = withFrameNanos { it }
        motion.turnGlowForever {
            val now = withFrameNanos { it }
            ((now - last) / 1_000_000_000f).also { last = now }
        }
    }
    // A hold that didn't lead to a link lets go.
    LaunchedEffect(state.phase) {
        if (state.phase != PairingPhase.CONNECTING && state.phase != PairingPhase.CONNECTED) {
            motion.hold.animateTo(0f, Motion.pop())
        }
    }
    // Connect and the actions rise into place shortly after the screen appears.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        awaitFrames()
        delay(150)
        enter.animateTo(1f, Motion.slideIn())
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AirColors.Background),
    ) {
        EdgeGlow(
            phase = { motion.glowPhase.value },
            intensity = { restingGlow + motion.hold.value * 0.9f },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = state.phase to state.peerName,
                transitionSpec = { textSwap() },
                contentKey = { it },
                label = "headline",
            ) { (phase, peerName) ->
                Headline(phase, peerName, state.code, state.error)
            }
            Spacer(Modifier.height(56.dp))
            HoldToConnectButton(
                motion = motion,
                enabled = state.phase == PairingPhase.FOUND || state.phase == PairingPhase.INCOMING,
                onHeld = { onAction(PairingAction.OnHoldComplete) },
                modifier = Modifier.graphicsLayer {
                    alpha = enter.value.coerceIn(0f, 1f)
                    translationY = (1f - enter.value) * 40.dp.toPx()
                },
            )
            Spacer(Modifier.weight(1f))
            Actions(
                state = state,
                onAction = onAction,
                modifier = Modifier.graphicsLayer {
                    alpha = enter.value.coerceIn(0f, 1f)
                    translationY = (1f - enter.value) * 56.dp.toPx()
                },
            )
        }

        PairingBloom(motion)
    }
}

@Composable
private fun Headline(phase: PairingPhase, peerName: String?, code: String?, error: DataError.Network?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp)) {
        val (lead, trailing) = when (phase) {
            PairingPhase.NEEDS_PERMISSION ->
                "Find the phone next to you" to
                    "Air uses Bluetooth, Wi-Fi and precise location to find it. Nothing is sent until you slide it."
            PairingPhase.SEARCHING ->
                "Looking for a phone nearby…" to "Open Air on the other phone and hold them side by side."
            PairingPhase.FOUND -> "Hold your phone next to" to "Then press and hold Connect."
            PairingPhase.INCOMING ->
                "Pair with" to "Check that both phones show this code, then press and hold Connect."
            PairingPhase.CONNECTING -> "Connecting to" to "Waiting for the other phone to accept."
            PairingPhase.CONNECTED ->
                "Paired with" to "Slide files off the edge to send them. Next time, the phones link up by themselves."
            PairingPhase.FAILED -> "Couldn't pair" to (error?.toPairingMessage() ?: DataError.Network.UNKNOWN.toPairingMessage())
        }
        Text(
            text = lead,
            style = MaterialTheme.typography.headlineMedium,
            color = AirColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        val showsName = phase == PairingPhase.FOUND || phase == PairingPhase.INCOMING ||
            phase == PairingPhase.CONNECTING || phase == PairingPhase.CONNECTED
        if (showsName && peerName != null) {
            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineMedium.copy(brush = AirColors.HeadlineGradient),
                textAlign = TextAlign.Center,
            )
        }
        // The same digits on both phones: the one in front of you is the one you're pairing with.
        if (code != null && (phase == PairingPhase.INCOMING || phase == PairingPhase.CONNECTING)) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.headlineMedium,
                color = AirColors.OnSurface,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = AirColors.OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Actions(state: PairingState, onAction: (PairingAction) -> Unit, modifier: Modifier = Modifier) {
    val needsSetting = state.phase == PairingPhase.FAILED &&
        (state.error == DataError.Network.PERMISSION_MISSING || state.error == DataError.Network.LOCATION_OFF)
    val needsPermission = state.phase == PairingPhase.NEEDS_PERMISSION || needsSetting
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            needsPermission -> {
                TextButton(onClick = { onAction(PairingAction.OnCloseClick) }) { Text("Not now") }
                PrimaryButton(
                    text = when {
                        state.error == DataError.Network.LOCATION_OFF -> "Turn on Location"
                        state.phase == PairingPhase.FAILED -> "Open settings"
                        else -> "Allow"
                    },
                    onClick = { onAction(PairingAction.OnAllowClick) },
                )
            }
            state.phase == PairingPhase.FAILED -> {
                TextButton(onClick = { onAction(PairingAction.OnCloseClick) }) { Text("Not now") }
                ScanButton(state, onAction)
                PrimaryButton("Try again") { onAction(PairingAction.OnRetryClick) }
            }
            state.phase == PairingPhase.INCOMING -> {
                TextButton(onClick = { onAction(PairingAction.OnDeclineClick) }) { Text("Decline") }
            }
            state.phase == PairingPhase.CONNECTING -> {
                TextButton(onClick = { onAction(PairingAction.OnCancelClick) }) { Text("Cancel") }
            }
            state.phase == PairingPhase.CONNECTED -> {
                TextButton(onClick = { onAction(PairingAction.OnDisconnectClick) }) { Text("Unpair") }
                if (state.canLineUp) TextButton(onClick = { onAction(PairingAction.OnLineUpClick) }) { Text("Line up") }
                PrimaryButton("Done") { onAction(PairingAction.OnCloseClick) }
            }
            else -> {
                TextButton(onClick = { onAction(PairingAction.OnCloseClick) }) { Text("Not now") }
                ScanButton(state, onAction)
            }
        }
    }
}

/** Pairing with a laptop by its QR code: where it can't be found by itself, or to skip comparing codes. */
@Composable
private fun ScanButton(state: PairingState, onAction: (PairingAction) -> Unit) {
    if (state.canScan) TextButton(onClick = { onAction(PairingAction.OnScanClick) }) { Text("Scan code") }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AirColors.OnSurface, contentColor = AirColors.Background),
    ) {
        Text(text)
    }
}

/** White light flooding out of Connect when the phones pair, with a prism-coloured rim, then fading. */
@Composable
private fun PairingBloom(motion: PairingMotion) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val t = motion.bloom.value
                if (t <= 0f) return@drawBehind
                val fade = motion.bloomFade.value
                val origin = motion.bloomCenter.takeIf { it.isSpecified } ?: center
                val reach = max(
                    max(hypot(origin.x, origin.y), hypot(size.width - origin.x, origin.y)),
                    max(hypot(origin.x, size.height - origin.y), hypot(size.width - origin.x, size.height - origin.y)),
                )
                val radius = reach * 1.15f * t
                if (radius <= 0f) return@drawBehind
                val core = AirColors.BloomCore
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to core.copy(alpha = fade),
                        0.75f to core.copy(alpha = 0.9f * fade),
                        1f to Color.Transparent,
                        center = origin,
                        radius = radius,
                    ),
                    radius = radius,
                    center = origin,
                )
                val fringe = AirColors.IridescentFringe
                val step = 0.12f / (fringe.size - 1)
                val stops = listOf(0.86f to Color.Transparent) +
                    fringe.mapIndexed { i, c -> 0.88f + step * i to c } +
                    listOf(1f to Color.Transparent)
                drawCircle(
                    brush = Brush.radialGradient(*stops.toTypedArray(), center = origin, radius = radius * 1.08f),
                    radius = radius * 1.08f,
                    center = origin,
                    alpha = 0.5f * fade,
                    blendMode = BlendMode.Plus,
                )
            },
    )
}

private fun PairingPhase.glow(): Float = when (this) {
    PairingPhase.NEEDS_PERMISSION -> 0.35f
    PairingPhase.SEARCHING -> 0.6f
    PairingPhase.FOUND -> 1f
    PairingPhase.INCOMING -> 1.1f
    PairingPhase.CONNECTING -> 1.3f
    PairingPhase.CONNECTED -> 1f
    PairingPhase.FAILED -> 0.3f
}

@Preview
@Composable
private fun PairingScreenFoundPreview() {
    AirTheme {
        PairingScreen(state = PairingState(phase = PairingPhase.FOUND, peerName = "Khalid's Pixel"), onAction = {})
    }
}

@Preview
@Composable
private fun PairingScreenIncomingPreview() {
    AirTheme {
        PairingScreen(
            state = PairingState(phase = PairingPhase.INCOMING, peerName = "Khalid's Pixel", code = "4821"),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun PairingScreenSearchingPreview() {
    AirTheme {
        PairingScreen(state = PairingState(phase = PairingPhase.SEARCHING, canScan = true), onAction = {})
    }
}
