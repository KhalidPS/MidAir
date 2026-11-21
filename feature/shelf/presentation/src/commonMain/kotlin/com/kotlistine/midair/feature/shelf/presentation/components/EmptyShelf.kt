package com.kotlistine.midair.feature.shelf.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kotlistine.midair.core.designsystem.theme.AirColors
import com.kotlistine.midair.core.designsystem.theme.AirDimens

@Composable
internal fun EmptyShelf(modifier: Modifier = Modifier) {
    val breathing = rememberInfiniteTransition(label = "emptyShelfBreathing")
    // Kept as State and read only inside drawBehind, so the pulse redraws without recomposing.
    val pulse = breathing.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        modifier = modifier.padding(horizontal = AirDimens.ScreenGutter * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(160.dp)
                .drawBehind {
                    val p = pulse.value
                    val radius = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(AirColors.GlowPink.copy(alpha = 0.30f + 0.15f * p), Color.Transparent),
                            center = center + Offset(-radius * 0.18f, 0f),
                            radius = radius * (0.80f + 0.12f * p),
                        ),
                        radius = radius,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(AirColors.GlowBlue.copy(alpha = 0.22f + 0.12f * (1f - p)), Color.Transparent),
                            center = center + Offset(radius * 0.22f, radius * 0.08f),
                            radius = radius * (0.70f + 0.12f * (1f - p)),
                        ),
                        radius = radius,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(AirColors.BloomCore.copy(alpha = 0.55f + 0.25f * p), Color.Transparent),
                            radius = radius * 0.28f,
                        ),
                        radius = radius * 0.28f,
                    )
                },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Your shelf is empty",
            style = MaterialTheme.typography.headlineMedium,
            color = AirColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap + to add photos and files, then slide them off the edge onto the device next to you.",
            style = MaterialTheme.typography.bodyMedium,
            color = AirColors.OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}
