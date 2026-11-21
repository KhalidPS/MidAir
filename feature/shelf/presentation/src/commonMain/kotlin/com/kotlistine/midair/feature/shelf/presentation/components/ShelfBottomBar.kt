package com.kotlistine.midair.feature.shelf.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.kotlistine.midair.core.designsystem.icons.AirIcons
import com.kotlistine.midair.core.designsystem.motion.Motion
import com.kotlistine.midair.core.designsystem.motion.textSwap
import com.kotlistine.midair.core.designsystem.theme.AirColors

/** Floating capsule from the reference design: paired device · air gestures · add · item count · more. */
@Composable
internal fun ShelfBottomBar(
    itemCount: Int,
    isPaired: Boolean,
    isLinkSlow: Boolean,
    onDevicesClick: () -> Unit,
    onAddClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether this device can watch for a hand; the hand button shows only then. */
    isAirGestureAvailable: Boolean = false,
    isAirGestureOn: Boolean = false,
    onAirGestureClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .height(60.dp)
            .clip(CircleShape)
            .background(AirColors.SurfaceRaised.copy(alpha = 0.94f))
            .border(1.dp, AirColors.Outline, CircleShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Opens pairing with the device next to this one.
        IconButton(onClick = onDevicesClick, modifier = Modifier.size(44.dp)) {
            Box {
                Icon(
                    imageVector = AirIcons.Devices,
                    contentDescription = when {
                        isLinkSlow -> "Paired, still on Bluetooth"
                        isPaired -> "Paired"
                        else -> "Pair with a device nearby"
                    },
                    tint = if (isPaired) AirColors.OnSurface else AirColors.OnSurfaceMuted,
                    modifier = Modifier.size(22.dp),
                )
                LinkMark(isPaired = isPaired, isSlow = isLinkSlow, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        if (isAirGestureAvailable) AirGestureButton(isOn = isAirGestureOn, onClick = onAirGestureClick)
        AddButton(onClick = onAddClick)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            AnimatedContent(targetState = itemCount, transitionSpec = { textSwap() }) { count ->
                Text(
                    text = if (count == 1) "1 item" else "$count items",
                    style = MaterialTheme.typography.labelLarge,
                    color = AirColors.OnSurface,
                )
            }
        }
        BarMenu(canClear = itemCount > 0, onClearClick = onClearClick)
    }
}

/** Turns air gestures on and off: lit while the camera may watch for a fist over the shelf. */
@Composable
private fun AirGestureButton(isOn: Boolean, onClick: () -> Unit) {
    val lit by animateFloatAsState(if (isOn) 1f else 0f, Motion.enter(240), label = "airLit")
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer { alpha = lit }
                .clip(CircleShape)
                .background(AirColors.OnSurface.copy(alpha = 0.14f)),
        )
        Icon(
            imageVector = AirIcons.Hand,
            contentDescription = if (isOn) "Turn off air gestures" else "Turn on air gestures",
            tint = lerp(AirColors.OnSurfaceMuted, AirColors.OnSurface, lit),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A small dot on the devices icon while paired: amber while the link is still on Bluetooth (slides are less smooth
 * then), cyan once it is on Wi-Fi. It fades between the two.
 */
@Composable
private fun LinkMark(isPaired: Boolean, isSlow: Boolean, modifier: Modifier = Modifier) {
    val color by animateColorAsState(if (isSlow) AirColors.GlowOrange else AirColors.GlowCyan, label = "linkMark")
    val shown by animateFloatAsState(if (isPaired) 1f else 0f, Motion.pop(), label = "linkMarkShown")
    Box(
        modifier = modifier
            .size(7.dp)
            .graphicsLayer {
                alpha = shown.coerceIn(0f, 1f)
                scaleX = shown
                scaleY = shown
            }
            .drawBehind { drawCircle(color) },
    )
}

@Composable
private fun AddButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, Motion.pop(), label = "addPress")
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(AirColors.OnSurface)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AirIcons.Add, contentDescription = "Add files", tint = AirColors.Background, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun BarMenu(canClear: Boolean, onClearClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp)) {
            Icon(AirIcons.More, contentDescription = "Shelf options", tint = AirColors.OnSurface, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = AirColors.SurfaceRaised,
        ) {
            DropdownMenuItem(
                text = { Text("Clear shelf") },
                enabled = canClear,
                onClick = {
                    expanded = false
                    onClearClick()
                },
            )
        }
    }
}
