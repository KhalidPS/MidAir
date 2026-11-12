package com.kotlistine.transferingfilebyair.feature.shelf.presentation.air

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kotlistine.transferingfilebyair.core.designsystem.icons.AirIcons
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.CatchableCardUi

/**
 * Tells the user a card is held over from the other phone, and how to take it: open the hand in front of this phone
 * (while the camera watches), or tap the hint.
 */
@Composable
internal fun CatchHint(
    catchable: CatchableCardUi?,
    isWatching: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AirHint(
        text = catchable?.let {
            if (isWatching) "Open your hand to catch “${it.name}”" else "Tap to catch “${it.name}”"
        },
        onTap = onTap,
        modifier = modifier,
    )
}

/**
 * While this user holds a card in the air: shows which, and that a tap puts it back. Without it a card whose catch
 * didn't work stayed in the hand until it timed out.
 */
@Composable
internal fun HoldHint(heldName: String?, onTap: () -> Unit, modifier: Modifier = Modifier) {
    AirHint(
        text = heldName?.let { "Holding “$it” · tap to put it back" },
        onTap = onTap,
        modifier = modifier,
    )
}

/** A breathing pill that drops in from the top while [text] isn't null; tapping it calls [onTap]. */
@Composable
private fun AirHint(text: String?, onTap: () -> Unit, modifier: Modifier = Modifier) {
    // Keeps the last text while the hint leaves, so it doesn't go blank on its way out.
    val shown = remember { LastNonNull<String>() }
    if (text != null) shown.value = text
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(Motion.enter(260)) + slideInVertically(Motion.slideIn()) { -it },
        exit = fadeOut(Motion.exit(200)) + slideOutVertically(Motion.exit(200)) { -it / 2 },
        modifier = modifier,
    ) {
        val label = shown.value ?: return@AnimatedVisibility
        val breath = rememberInfiniteTransition(label = "hintBreath")
        val glow by breath.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "hintGlow",
        )
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(AirColors.SurfaceRaised.copy(alpha = 0.96f))
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(AirColors.GlowSweep.map { it.copy(alpha = 0.3f + 0.5f * glow) }),
                    shape = CircleShape,
                )
                .clickable(role = Role.Button, onClick = onTap)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = AirIcons.Hand,
                contentDescription = null,
                tint = AirColors.OnSurface,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        val scale = 0.92f + 0.08f * glow
                        scaleX = scale
                        scaleY = scale
                    },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = AirColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

private class LastNonNull<T : Any> {
    var value: T? = null
}
