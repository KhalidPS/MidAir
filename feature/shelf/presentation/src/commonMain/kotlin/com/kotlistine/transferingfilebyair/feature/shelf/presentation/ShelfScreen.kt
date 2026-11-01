package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotlistine.transferingfilebyair.core.designsystem.effects.recede
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.awaitFrames
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirDimens
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.core.domain.shelf.FileKind
import com.kotlistine.transferingfilebyair.core.media.VideoEngine
import com.kotlistine.transferingfilebyair.core.presentation.ObserveAsEvents
import com.kotlistine.transferingfilebyair.core.presentation.file.OpenFileResult
import com.kotlistine.transferingfilebyair.core.presentation.file.rememberFileOpener
import com.kotlistine.transferingfilebyair.core.presentation.file.rememberFilePickerLauncher
import com.kotlistine.transferingfilebyair.core.transfer.domain.ExitEdge
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.CardVideo
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.EmptyShelf
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.LocalCardVideo
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.ShelfBottomBar
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.ShelfGrid
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.components.arrivingKey
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ArrivingItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.ArrivalWave
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.EdgeBloom
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.IncomingCard
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.IncomingSlideState
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.LiftedCard
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideFrame
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideListener
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlotRegistry
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.rememberIncomingSlideState
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.rememberSlideState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ShelfRoot(viewModel: ShelfViewModel = koinViewModel(), onDevicesClick: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val filePicker = rememberFilePickerLauncher { uris -> viewModel.onAction(ShelfAction.OnFilesPicked(uris)) }
    val fileOpener = rememberFileOpener()
    val incomingSlide = rememberIncomingSlideState()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onAction(ShelfAction.OnScreenHidden) }

    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // The snackbar's own lifetime decides the send: it can stay up longer than its nominal duration (accessibility
    // timeouts), and the item must stay recoverable for as long as Undo is visible.
    fun offerUndo(event: ShelfEvent.ShowSent) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = try {
                snackbarHostState.showSnackbar(event.toMessage(), UndoLabel, duration = SnackbarDuration.Short)
            } catch (e: CancellationException) {
                // The screen went away mid-offer: bring the item back rather than lose it.
                viewModel.onAction(ShelfAction.OnUndoSend(event.id))
                throw e
            }
            viewModel.onAction(
                if (result == SnackbarResult.ActionPerformed) {
                    ShelfAction.OnUndoSend(event.id)
                } else {
                    ShelfAction.OnUndoOfferClosed(event.id)
                },
            )
        }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ShelfEvent.OpenFile -> when (fileOpener.open(event.uri, event.mimeType)) {
                OpenFileResult.OPENED -> Unit
                OpenFileResult.NO_APP -> showMessage(NoAppToOpenMessage)
                OpenFileResult.NOT_ACCESSIBLE -> showMessage(FileNotAccessibleMessage)
            }
            is ShelfEvent.ShowSent -> offerUndo(event)
            is ShelfEvent.ShowAddFailed -> showMessage(event.toMessage())
            is ShelfEvent.ShowError -> showMessage(event.error.toMessage())
            is ShelfEvent.PeerCardLifted -> incomingSlide.onLifted(event.card)
            is ShelfEvent.PeerCardMoved -> incomingSlide.onMoved(
                id = event.id,
                leftPx = event.leftPx,
                topPx = event.topPx,
                velocityXPxPerS = event.velocityXPxPerS,
                velocityYPxPerS = event.velocityYPxPerS,
                tiltDegrees = event.tiltDegrees,
                arrival = event.arrival,
                entryEdge = event.entryEdge,
                sentAtMillis = event.sentAtMillis,
                leadMillis = event.leadMillis,
            )
            is ShelfEvent.PeerCardWithdrawn -> incomingSlide.onWithdrawn(event.id)
            is ShelfEvent.PeerCardArrived ->
                incomingSlide.onArrived(event.id, Offset(event.velocityXPxPerS, event.velocityYPxPerS))
            is ShelfEvent.ShowSendFailed -> scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(event.toMessage(), RetryLabel, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) viewModel.onAction(ShelfAction.OnRetrySend(event.id))
            }
        }
    }

    ShelfScreen(
        state = state,
        onAction = { action ->
            when (action) {
                ShelfAction.OnAddClick -> filePicker.launch()
                ShelfAction.OnDevicesClick -> onDevicesClick()
                else -> viewModel.onAction(action)
            }
        },
        snackbarHostState = snackbarHostState,
        incomingSlide = incomingSlide,
        videoEngine = viewModel.videoEngine,
    )
}

private enum class ShelfContent { Loading, Empty, Items }

private const val PictureKeepMillis = 1_500L

@Composable
fun ShelfScreen(
    state: ShelfState,
    onAction: (ShelfAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    incomingSlide: IncomingSlideState = rememberIncomingSlideState(),
    /** Draws the picture of [ShelfState.playing]. */
    videoEngine: VideoEngine? = null,
) {
    // Bottom chrome rises into place shortly after the first frame.
    val barEnter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        awaitFrames()
        delay(120)
        barEnter.animateTo(1f, Motion.slideIn())
    }

    val slideState = rememberSlideState(
        listener = object : SlideListener {
            override fun onLifted(id: String, cardWidthPx: Float, cardHeightPx: Float, thumbnail: ByteArray?) =
                onAction(ShelfAction.OnSlideLifted(id, cardWidthPx, cardHeightPx, thumbnail))

            override fun onMoved(frame: SlideFrame) = onAction(ShelfAction.OnSlideMoved(frame))
            override fun onReturned(id: String) = onAction(ShelfAction.OnSlideReturned(id))
            override fun onSent(id: String, edge: ExitEdge) = onAction(ShelfAction.OnItemSent(id, edge))
        },
        isMirrored = state.isPaired,
    )
    LaunchedEffect(state.items) { slideState.followShelf(state.items) }

    val gridState = rememberLazyStaggeredGridState()
    val slots = remember { SlotRegistry() }

    // The slot's rect in overlay coordinates, from both corners so it is right even while the shelf is scaled.
    fun slotRect(id: String): Rect? {
        val slot = slots[id] ?: return null
        val size = slot.size
        val topLeft = slideState.toOverlay(slot, Offset.Zero) ?: return null
        val bottomRight = slideState.toOverlay(slot, Offset(size.width.toFloat(), size.height.toFloat())) ?: return null
        return Rect(topLeft, bottomRight)
    }

    // A card let go by the peer flies into its slot at the top of the shelf, so the top is brought into view.
    SideEffect { incomingSlide.slotOf = { id -> slotRect(arrivingKey(id)) } }
    val newestArriving = state.arriving.firstOrNull()?.id
    LaunchedEffect(newestArriving) {
        if (newestArriving != null && gridState.layoutInfo.totalItemsCount > 0) gridState.animateScrollToItem(0)
    }
    // Pictures are only needed while their cards wait, and for a moment while the received item takes over.
    LaunchedEffect(state.arriving) {
        delay(PictureKeepMillis)
        val keep = state.arriving.mapTo(HashSet()) { it.id }
        incomingSlide.card?.let { keep += it.id }
        incomingSlide.pictures.keys.retainAll(keep)
    }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val content = when {
        state.isLoading -> ShelfContent.Loading
        state.items.isEmpty() && state.arriving.isEmpty() -> ShelfContent.Empty
        else -> ShelfContent.Items
    }

    val playing = state.playing
    val cardVideo = if (videoEngine != null && playing != null) {
        CardVideo(videoEngine, playing.itemId, playing.isPaused)
    } else {
        null
    }

    // Back to front: the shelf (recedes while a card is lifted or arriving), the edge blooms, the cards in flight,
    // then the snackbar, which stays sharp so "Undo" is readable while the shelf is still clearing.
    // Clipped, so a card past the edge is cut there even when the screen is not the whole window (Twin Simulator).
    CompositionLocalProvider(LocalCardVideo provides cardVideo) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .background(AirColors.Background)
                .onPlaced(slideState::attachOverlay)
                .onSizeChanged { size ->
                    incomingSlide.containerSize = size.toSize()
                    onAction(ShelfAction.OnViewportChanged(size.width, size.height))
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .recede(progress = { max(slideState.focus.value, incomingSlide.focus()) }),
            ) {
                AnimatedContent(
                    targetState = content,
                    transitionSpec = { fadeIn(tween(320, delayMillis = 90)) togetherWith fadeOut(tween(160)) },
                    modifier = Modifier.fillMaxSize(),
                ) { target ->
                    when (target) {
                        ShelfContent.Loading -> Box(Modifier.fillMaxSize())
                        ShelfContent.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyShelf()
                        }
                        ShelfContent.Items -> ShelfGrid(
                            items = state.items,
                            onAction = onAction,
                            slideState = slideState,
                            slots = slots,
                            arriving = state.arriving,
                            arrivedFrom = state.arrivedFrom,
                            pictures = incomingSlide.pictures,
                            flyingId = incomingSlide.flyingId,
                            gridState = gridState,
                            contentPadding = PaddingValues(
                                start = AirDimens.ScreenGutter,
                                end = AirDimens.ScreenGutter,
                                top = insets.calculateTopPadding() + 12.dp,
                                bottom = insets.calculateBottomPadding() + 112.dp,
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Content melts under the status bar and the floating bar instead of being cut off.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(insets.calculateTopPadding() + 20.dp)
                        .background(Brush.verticalGradient(listOf(AirColors.Background, Color.Transparent))),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(insets.calculateBottomPadding() + 120.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, AirColors.Background))),
                )

                ShelfBottomBar(
                    itemCount = state.items.size,
                    isPaired = state.isPaired,
                    isLinkSlow = state.isLinkSlow,
                    onDevicesClick = { onAction(ShelfAction.OnDevicesClick) },
                    onAddClick = { onAction(ShelfAction.OnAddClick) },
                    onClearClick = { onAction(ShelfAction.OnClearClick) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .graphicsLayer {
                            val t = barEnter.value
                            alpha = t.coerceIn(0f, 1f)
                            translationY = (1f - t) * 56.dp.toPx()
                        },
                )
            }

            EdgeBloom(bloom = { width -> slideState.bloom(width) })
            EdgeBloom(bloom = { incomingSlide.bloom() })
            ArrivalWave(wave = { incomingSlide.wave() })
            // Absolute, not start-aligned: the cards' offsets are overlay pixels, also in right-to-left layouts.
            IncomingCard(incomingSlide, Modifier.align(AbsoluteAlignment.TopLeft))
            LiftedCard(slideState, Modifier.align(AbsoluteAlignment.TopLeft))

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 84.dp),
            )
        }
    }

    if (state.isClearConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { onAction(ShelfAction.OnClearDismiss) },
            title = { Text("Clear the shelf?") },
            text = { Text("Files stay on your device. They're only removed from the shelf.") },
            confirmButton = {
                TextButton(onClick = { onAction(ShelfAction.OnClearConfirm) }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { onAction(ShelfAction.OnClearDismiss) }) { Text("Cancel") }
            },
            containerColor = AirColors.SurfaceRaised,
        )
    }
}

@Preview
@Composable
private fun ShelfScreenEmptyPreview() {
    AirTheme {
        ShelfScreen(state = ShelfState(isLoading = false), onAction = {})
    }
}

@Preview
@Composable
private fun ShelfScreenItemsPreview() {
    AirTheme {
        ShelfScreen(
            state = ShelfState(
                isLoading = false,
                items = listOf(
                    previewItem("1", "Golden Hour.jpg", "367 KB · 13:25", FileKind.IMAGE, "JPG", 4f / 3f),
                    previewItem("2", "Meeting notes.md", "208 bytes · 13:25", FileKind.TEXT, "MD", 0.78f),
                    previewItem("3", "Strawberries.jpg", "319 KB · 13:25", FileKind.IMAGE, "JPG", 1.2f),
                    previewItem("4", "Q4 Report.pdf", "16 KB · 13:25", FileKind.PDF, "PDF", 0.78f),
                    previewItem("5", "Walrus.mp4", "18 MB · 13:24", FileKind.VIDEO, "MP4", 1.6f),
                ),
                arriving = listOf(ArrivingItemUi(id = "9", name = "River Valley.jpg", aspectRatio = 4f / 3f, progress = 0.42f)),
            ),
            onAction = {},
        )
    }
}

private fun previewItem(id: String, name: String, details: String, kind: FileKind, badge: String, ratio: Float) =
    ShelfItemUi(
        id = id,
        name = name,
        details = details,
        kind = kind,
        badge = badge,
        aspectRatio = ratio,
        uri = "",
        mimeType = "",
    )
