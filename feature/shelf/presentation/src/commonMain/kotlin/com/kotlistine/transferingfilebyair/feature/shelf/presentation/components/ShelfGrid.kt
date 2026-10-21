package com.kotlistine.transferingfilebyair.feature.shelf.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.awaitFrames
import com.kotlistine.transferingfilebyair.core.designsystem.motion.stagger
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirDimens
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.ShelfAction
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlideState
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.SlotCoordinates
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.liftToSlide
import kotlin.math.min

// Cascade: each card starts 70 ms after the previous one (capped at the 8th), each slice eases over the remainder.
private const val CascadeStepMs = 70
private const val CascadeMaxSteps = 7
private const val CascadeSliceMs = 520
private const val CascadeTotalMs = CascadeSliceMs + CascadeStepMs * CascadeMaxSteps
private const val CascadeRestingAlpha = 0.25f
private val CascadeRise = 40.dp

@Composable
internal fun ShelfGrid(
    items: List<ShelfItemUi>,
    onAction: (ShelfAction) -> Unit,
    slideState: SlideState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // One clock for the whole first-load cascade; every card reads its own slice in the draw phase.
    val intro = remember { Animatable(0f) }
    // Ids present when the grid first appeared play the cascade; anything added later pops in instead.
    val seenIds = remember { items.mapTo(HashSet()) { it.id } }
    // Forget items that left, so one that comes back (an undone send) pops in again.
    SideEffect { seenIds.retainAll(items.mapTo(HashSet()) { it.id }) }

    LaunchedEffect(Unit) {
        awaitFrames()
        intro.animateTo(1f, tween(CascadeTotalMs, easing = LinearEasing))
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(AirDimens.GridSpacing),
        verticalItemSpacing = 18.dp,
        modifier = modifier,
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val isNew = remember(item.id) { seenIds.add(item.id) }
            val appear = remember(item.id) { Animatable(if (isNew) 0f else 1f) }
            LaunchedEffect(item.id) {
                if (appear.value < 1f) appear.animateTo(1f, Motion.pop())
            }
            val cascadeDelay = min(index, CascadeMaxSteps) * CascadeStepMs / CascadeTotalMs.toFloat()
            val currentItem by rememberUpdatedState(item)
            val slot = remember(item.id) { SlotCoordinates() }

            ShelfCard(
                item = item,
                onOpenClick = { onAction(ShelfAction.OnOpenClick(item.id)) },
                onRemoveClick = { onAction(ShelfAction.OnRemoveClick(item.id)) },
                previewModifier = Modifier
                    .liftToSlide(slideState, key = item.id, slot = slot) { slideState.tryLift(currentItem, it) }
                    // While its copy is in the overlay, the slot shows nothing (the label stays, under the blur).
                    .graphicsLayer { alpha = if (slideState.hiddenId == item.id) 0f else 1f },
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = null, // new cards pop in below instead of a plain fade
                        placementSpec = Motion.slideIn(),
                        fadeOutSpec = Motion.exit(200),
                    )
                    .graphicsLayer {
                        val rise = FastOutSlowInEasing.transform(stagger(intro.value, cascadeDelay))
                        val pop = appear.value
                        alpha = (CascadeRestingAlpha + (1f - CascadeRestingAlpha) * rise) * pop.coerceIn(0f, 1f)
                        translationY = (1f - rise) * CascadeRise.toPx()
                        val scale = 0.85f + 0.15f * pop
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}
