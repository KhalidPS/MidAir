package com.kotlistine.transferingfilebyair.feature.shelf.presentation.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirDimens
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.ArrivingItemUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.slide.CardName

/**
 * A card from the paired device in its slot while its file comes in: the sender's picture, dimmed, with a ring
 * showing how much has arrived. A tap stops the transfer. Laid out like [ShelfCard], so the item that replaces it
 * once the file is here takes exactly its place.
 */
@Composable
internal fun ArrivingCard(
    item: ArrivingItemUi,
    picture: ImageBitmap?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    previewModifier: Modifier = Modifier,
) {
    val progress = animateFloatAsState(item.progress, tween(180, easing = FastOutSlowInEasing))
    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(item.aspectRatio)
                .clip(RoundedCornerShape(AirDimens.CardCorner))
                .clickable(onClickLabel = "Stop receiving", onClick = onTap)
                .then(previewModifier)
                .background(AirColors.SurfaceRaised)
                .drawWithContent {
                    drawContent()
                    drawProgressRing(progress.value)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) {
                Image(bitmap = picture, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                CardName(item.name)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(start = 2.dp, top = 2.dp, end = 34.dp)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelLarge,
                color = AirColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = "Receiving · ${(item.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = AirColors.OnSurfaceMuted,
                maxLines = 1,
            )
        }
    }
}

/** A dimmed card with a ring filling clockwise from the top: how much of the file has arrived. */
private fun DrawScope.drawProgressRing(progress: Float) {
    drawRect(AirColors.Background.copy(alpha = 0.35f))
    val radius = size.minDimension * 0.18f
    val stroke = 4.dp.toPx()
    val topLeft = Offset(center.x - radius, center.y - radius)
    val ringSize = Size(radius * 2, radius * 2)
    drawCircle(AirColors.OnSurface.copy(alpha = 0.25f), radius = radius, center = center, style = Stroke(stroke))
    drawArc(
        color = AirColors.OnSurface,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        topLeft = topLeft,
        size = ringSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

@Preview
@Composable
private fun ArrivingCardPreview() {
    AirTheme {
        ArrivingCard(
            item = ArrivingItemUi(id = "9", name = "River Valley.jpg", aspectRatio = 4f / 3f, progress = 0.42f),
            picture = null,
            onTap = {},
            modifier = Modifier.width(180.dp),
        )
    }
}
