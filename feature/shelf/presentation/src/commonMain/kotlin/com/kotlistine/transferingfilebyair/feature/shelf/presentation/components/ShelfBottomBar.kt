package com.kotlistine.transferingfilebyair.feature.shelf.presentation.components

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kotlistine.transferingfilebyair.core.designsystem.icons.AirIcons
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.motion.textSwap
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors

/** Floating capsule from the reference design: paired device · add · item count · more. */
@Composable
internal fun ShelfBottomBar(
    itemCount: Int,
    onDevicesClick: () -> Unit,
    onAddClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            Icon(
                imageVector = AirIcons.Devices,
                contentDescription = "Pair with a device nearby",
                tint = AirColors.OnSurfaceMuted,
                modifier = Modifier.size(22.dp),
            )
        }
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
