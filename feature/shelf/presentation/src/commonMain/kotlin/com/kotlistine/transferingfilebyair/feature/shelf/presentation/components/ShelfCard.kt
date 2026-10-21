package com.kotlistine.transferingfilebyair.feature.shelf.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kotlistine.transferingfilebyair.core.designsystem.icons.AirIcons
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirDimens
import com.kotlistine.transferingfilebyair.core.domain.shelf.FileKind
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ShelfItemUi

@Composable
internal fun ShelfCard(
    item: ShelfItemUi,
    onOpenClick: () -> Unit,
    onRemoveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FilePreview(
            item = item,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(item.aspectRatio)
                .clip(RoundedCornerShape(AirDimens.CardCorner))
                .clickable(onClick = onOpenClick),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 2.dp, top = 2.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = AirColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = item.details,
                    style = MaterialTheme.typography.labelMedium,
                    color = AirColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
            ItemMenu(onOpenClick = onOpenClick, onRemoveClick = onRemoveClick)
        }
    }
}

@Composable
private fun ItemMenu(onOpenClick: () -> Unit, onRemoveClick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = AirIcons.More,
                contentDescription = "More options",
                tint = AirColors.OnSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = AirColors.SurfaceRaised,
        ) {
            DropdownMenuItem(
                text = { Text("Open") },
                onClick = {
                    expanded = false
                    onOpenClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from shelf") },
                onClick = {
                    expanded = false
                    onRemoveClick()
                },
            )
        }
    }
}

@Composable
private fun FilePreview(item: ShelfItemUi, modifier: Modifier = Modifier) {
    when (item.kind) {
        FileKind.IMAGE -> ImagePreview(item, modifier)
        FileKind.VIDEO -> VideoPreview(item, modifier)
        FileKind.PDF, FileKind.TEXT -> PagePreview(item, modifier)
        FileKind.OTHER -> GenericPreview(item, modifier)
    }
}

/** Decoded by the app image loader: photos, video frames and first PDF pages. Crossfades over its resting state. */
@Composable
private fun Thumbnail(item: ShelfItemUi, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(item.uri)
            .crossfade(260)
            .build(),
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        alignment = alignment,
        modifier = modifier,
    )
}

@Composable
private fun ImagePreview(item: ShelfItemUi, modifier: Modifier) {
    Box(modifier.background(AirColors.SurfaceRaised)) {
        Thumbnail(item, Modifier.fillMaxSize())
    }
}

@Composable
private fun VideoPreview(item: ShelfItemUi, modifier: Modifier) {
    Box(
        modifier.background(
            Brush.linearGradient(listOf(AirColors.SurfaceRaised, AirColors.GlowBlue.copy(alpha = 0.22f))),
        ),
    ) {
        Thumbnail(item, Modifier.fillMaxSize())
        // Keeps the type badge readable over bright frames.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, AirColors.Background.copy(alpha = 0.6f)))),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(44.dp)
                .clip(CircleShape)
                .background(AirColors.Background.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AirIcons.Play, contentDescription = null, tint = AirColors.OnSurface, modifier = Modifier.size(22.dp))
        }
        TypeBadge(item.badge, Modifier.align(Alignment.BottomStart))
    }
}

/**
 * A sheet of paper, like the document cards in the reference design: the PDF's first page or the text file's
 * opening lines. Faint drawn lines are the resting state while a page renders, or when there is no content.
 */
@Composable
private fun PagePreview(item: ShelfItemUi, modifier: Modifier) {
    val isPdf = item.kind == FileKind.PDF
    val text = item.textPreview
    Box(modifier.background(AirColors.SurfaceRaised)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(AirColors.Paper)
                .drawBehind { if (text == null) drawPaperLines(withHeading = isPdf) },
        ) {
            when {
                isPdf -> Thumbnail(item, Modifier.fillMaxSize(), alignment = Alignment.TopCenter)
                text != null -> Text(
                    text = text,
                    style = PagePreviewTextStyle,
                    color = AirColors.PaperInk,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        TypeBadge(item.badge, Modifier.align(Alignment.BottomStart))
    }
}

private val PagePreviewTextStyle = TextStyle(fontSize = 6.sp, lineHeight = 8.5.sp)

private fun DrawScope.drawPaperLines(withHeading: Boolean) {
    val margin = size.width * 0.12f
    val lineHeight = 3.dp.toPx()
    val gap = 9.dp.toPx()
    var top = margin
    if (withHeading) {
        drawRoundRect(
            color = AirColors.GlowBlue.copy(alpha = 0.85f),
            topLeft = Offset(margin, top),
            size = Size(size.width * 0.5f, lineHeight * 2.4f),
            cornerRadius = CornerRadius(lineHeight),
        )
        top += lineHeight * 2.4f + gap
    }
    var index = 0
    while (top + lineHeight < size.height - margin) {
        drawRoundRect(
            color = AirColors.PaperLine,
            topLeft = Offset(margin, top),
            size = Size((size.width - margin * 2) * lineWidths[index % lineWidths.size], lineHeight),
            cornerRadius = CornerRadius(lineHeight / 2),
        )
        top += lineHeight + gap
        index++
    }
}

@Composable
private fun GenericPreview(item: ShelfItemUi, modifier: Modifier) {
    Box(modifier.background(AirColors.SurfaceRaised), contentAlignment = Alignment.Center) {
        Text(
            text = item.badge ?: "FILE",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = AirColors.OnSurfaceMuted,
        )
    }
}

@Composable
private fun TypeBadge(badge: String?, modifier: Modifier = Modifier) {
    if (badge == null) return
    Text(
        text = badge,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = AirColors.OnSurface,
        modifier = modifier.padding(start = 14.dp, bottom = 9.dp),
    )
}

private val lineWidths = floatArrayOf(0.92f, 0.78f, 0.86f, 0.64f, 0.9f, 0.7f)
