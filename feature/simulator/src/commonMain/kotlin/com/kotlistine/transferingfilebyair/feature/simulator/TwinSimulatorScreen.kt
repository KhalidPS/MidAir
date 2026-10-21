package com.kotlistine.transferingfilebyair.feature.simulator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.setSingletonImageLoaderFactory
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.core.presentation.ObserveAsEvents
import com.kotlistine.transferingfilebyair.core.presentation.image.newAirImageLoader
import com.kotlistine.transferingfilebyair.core.transfer.domain.ScreenDensity
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.ShelfRoot
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.ShelfViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/** Width / height of each virtual phone screen. */
private const val TwinAspect = 0.46f
private val TwinCorner = 26.dp
private val BezelRing = 3.dp

/** Debug entry point: the simulator as a whole app (theme, image loading). Koin must include the simulator module. */
@Composable
fun TwinSimulatorApp() {
    setSingletonImageLoaderFactory(::newAirImageLoader)
    AirTheme {
        TwinSimulatorRoot()
    }
}

@Composable
fun TwinSimulatorRoot(viewModel: SimulatorViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val screenDensity = koinInject<ScreenDensity>()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            SimulatorEvent.TwinsReset -> scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("Twins reset: left has your shelf, right is empty.")
            }
        }
    }

    // The gap is drawn at its physical size, so a card crossing it covers the same distance a real one would.
    val gapWidth = with(LocalDensity.current) { screenDensity.xMmToPx(state.gapMm).toDp() }

    TwinSimulatorScreen(
        state = state,
        onAction = viewModel::onAction,
        gapWidth = gapWidth,
        snackbarHostState = snackbarHostState,
        leftTwin = {
            if (state.isReady) {
                ShelfRoot(viewModel(key = "twin-left") { ShelfViewModel(viewModel.left.shelf, screenDensity, viewModel.left.peer) })
            }
        },
        rightTwin = {
            if (state.isReady) {
                ShelfRoot(viewModel(key = "twin-right") { ShelfViewModel(viewModel.right.shelf, screenDensity, viewModel.right.peer) })
            }
        },
    )
}

@Composable
fun TwinSimulatorScreen(
    state: SimulatorState,
    onAction: (SimulatorAction) -> Unit,
    gapWidth: Dp,
    leftTwin: @Composable () -> Unit,
    rightTwin: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AirColors.Background)
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Controls(state, onAction)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val twinWidth = min((maxWidth - gapWidth) / 2, maxHeight * TwinAspect)
                val twinHeight = twinWidth / TwinAspect
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TwinFrame(twinWidth, twinHeight, leftTwin)
                    Spacer(Modifier.width(gapWidth))
                    TwinFrame(twinWidth, twinHeight, rightTwin)
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Controls(state: SimulatorState, onAction: (SimulatorAction) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Twin Simulator", style = MaterialTheme.typography.titleMedium, color = AirColors.OnSurface)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onAction(SimulatorAction.OnResetClick) }) { Text("Reset twins") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LabeledSlider(
                label = "Latency ${state.latencyMs} ms",
                value = state.latencyMs.toFloat(),
                range = 0f..150f,
                onChange = { onAction(SimulatorAction.OnLatencyChange(it.roundToInt())) },
                modifier = Modifier.weight(1f),
            )
            LabeledSlider(
                label = "Gap ${state.gapMm.roundToInt()} mm",
                value = state.gapMm,
                range = 2f..40f,
                onChange = { onAction(SimulatorAction.OnGapChange(it)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AirColors.OnSurfaceMuted)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AirColors.OnSurface,
                activeTrackColor = AirColors.GlowBlue,
                inactiveTrackColor = AirColors.SurfaceRaised,
            ),
        )
    }
}

/** A virtual phone: its screen clipped to rounded corners, with a thin bezel ring drawn just outside it. */
@Composable
private fun TwinFrame(width: Dp, height: Dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(width, height)
            .drawWithContent {
                drawContent()
                val ring = BezelRing.toPx()
                drawRoundRect(
                    color = AirColors.Outline,
                    topLeft = Offset(-ring, -ring),
                    size = Size(size.width + ring * 2, size.height + ring * 2),
                    cornerRadius = CornerRadius(TwinCorner.toPx() + ring),
                    style = Stroke(width = ring / 2),
                )
            }
            .clip(RoundedCornerShape(TwinCorner)),
    ) {
        content()
    }
}

@Preview
@Composable
private fun TwinSimulatorScreenPreview() {
    AirTheme {
        TwinSimulatorScreen(
            state = SimulatorState(latencyMs = 30, isReady = true),
            onAction = {},
            gapWidth = 30.dp,
            leftTwin = { Box(Modifier.fillMaxSize().background(AirColors.Surface)) },
            rightTwin = { Box(Modifier.fillMaxSize().background(AirColors.Surface)) },
        )
    }
}
