package com.kotlistine.transferingfilebyair.feature.shelf.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kotlistine.transferingfilebyair.core.designsystem.motion.Motion
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirColors
import com.kotlistine.transferingfilebyair.core.designsystem.theme.AirTheme
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferDirection
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKey
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferStatus
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.TransferUi
import com.kotlistine.transferingfilebyair.feature.shelf.presentation.model.TransfersUi

/**
 * A small pill while files move either way: how many go each way and a ring for all of them together. A tap opens
 * the list ([TransfersSheet]). Hidden when nothing moves; it stays a moment for files that just finished.
 */
@Composable
internal fun TransfersPill(transfers: () -> TransfersUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = transfers()
    val active = current.active
    AnimatedVisibility(
        visible = active.isNotEmpty(),
        enter = fadeIn(Motion.enter(220)) + scaleIn(Motion.pop(), initialScale = 0.8f) +
            slideInVertically(Motion.slideIn()) { it / 2 },
        exit = fadeOut(Motion.exit(180)) + scaleOut(Motion.exit(180), targetScale = 0.9f),
        modifier = modifier,
    ) {
        val sending = active.count { it.isSending }
        val receiving = active.size - sending
        val overall by animateFloatAsState(current.overallFraction, Motion.preciseLanding())
        val label = buildString {
            if (sending > 0) append("Sending $sending")
            if (sending > 0 && receiving > 0) append(" · ")
            if (receiving > 0) append("Receiving $receiving")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(AirColors.SurfaceRaised)
                .border(1.dp, AirColors.Outline, CircleShape)
                .clickable(onClickLabel = "Show transfers", onClick = onClick)
                .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
                .semantics { contentDescription = "$label, ${(overall * 100).toInt()} percent" },
        ) {
            Canvas(Modifier.size(18.dp)) { drawRing({ overall }, strokeWidth = 2.5.dp.toPx()) }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = AirColors.OnSurface, maxLines = 1)
        }
    }
}

/** Every file moving either way, each with its own progress and its own stop button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransfersSheet(
    transfers: () -> TransfersUi,
    onCancel: (TransferKey) -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AirColors.Surface,
        contentColor = AirColors.OnSurface,
    ) {
        TransfersList(transfers(), onCancel, onCancelAll, onClearFinished)
    }
}

@Composable
private fun TransfersList(
    current: TransfersUi,
    onCancel: (TransferKey) -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Transfers", style = MaterialTheme.typography.titleLarge, color = AirColors.OnSurface)
                Text(current.summary(), style = MaterialTheme.typography.bodyMedium, color = AirColors.OnSurfaceMuted)
            }
            if (current.hasFinished) TextButton(onClick = onClearFinished) { Text("Clear") }
            if (current.active.size > 1) TextButton(onClick = onCancelAll) { Text("Stop all") }
        }
        if (current.transfers.isEmpty()) {
            Text(
                text = "Files you slide over, or that come in, show here while they move.",
                style = MaterialTheme.typography.bodyMedium,
                color = AirColors.OnSurfaceMuted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Newest first, as the shelf shows its cards.
                items(current.transfers.asReversed(), key = { "${it.key.direction}:${it.key.itemId}" }) { transfer ->
                    TransferRow(transfer, onCancel = { onCancel(transfer.key) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
private fun TransferRow(transfer: TransferUi, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(transfer.fraction, Motion.preciseLanding())
    val accent = when (transfer.status) {
        TransferStatus.FAILED -> AirColors.GlowPink
        TransferStatus.CANCELLED -> AirColors.OnSurfaceMuted
        TransferStatus.DONE -> AirColors.GlowCyan
        else -> if (transfer.isSending) AirColors.GlowOrange else AirColors.GlowBlue
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(AirColors.SurfaceRaised),
        ) {
            Canvas(Modifier.size(18.dp)) { drawArrow(up = transfer.isSending, color = accent) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = transfer.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AirColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (transfer.percent.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(transfer.percent, style = MaterialTheme.typography.labelMedium, color = AirColors.OnSurfaceMuted)
                }
            }
            Spacer(Modifier.height(6.dp))
            // Read in the draw phase: the bar eases between readings without recomposing the row.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AirColors.Outline)
                    .drawBehind {
                        val shown = if (transfer.status == TransferStatus.QUEUED) 0f else fraction
                        drawRect(accent, size = Size(size.width * shown, size.height))
                    },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = transfer.detail,
                style = MaterialTheme.typography.labelMedium,
                color = if (transfer.status == TransferStatus.FAILED) AirColors.GlowPink else AirColors.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!transfer.isFinished) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Stop ${transfer.name}", onClick = onCancel),
            ) {
                Canvas(Modifier.size(12.dp)) { drawCross(AirColors.OnSurfaceMuted) }
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

private fun TransfersUi.summary(): String {
    val active = active
    if (active.isEmpty()) return if (transfers.isEmpty()) "Nothing moving" else "All done"
    val sending = active.count { it.isSending }
    val receiving = active.size - sending
    val queued = active.count { it.status == TransferStatus.QUEUED }
    return buildString {
        if (sending > 0) append("$sending sending")
        if (sending > 0 && receiving > 0) append(" · ")
        if (receiving > 0) append("$receiving receiving")
        if (queued > 0) append(" · $queued waiting their turn")
    }
}

/** A ring filling clockwise from the top. */
private fun DrawScope.drawRing(fraction: () -> Float, strokeWidth: Float) {
    val radius = (size.minDimension - strokeWidth) / 2f
    val topLeft = Offset(center.x - radius, center.y - radius)
    val ring = Size(radius * 2, radius * 2)
    drawCircle(AirColors.OnSurface.copy(alpha = 0.2f), radius = radius, style = Stroke(strokeWidth))
    drawArc(
        color = AirColors.OnSurface,
        startAngle = -90f,
        sweepAngle = 360f * fraction().coerceIn(0f, 1f),
        useCenter = false,
        topLeft = topLeft,
        size = ring,
        style = Stroke(strokeWidth, cap = StrokeCap.Round),
    )
}

/** An arrow up (sending) or down (receiving). */
private fun DrawScope.drawArrow(up: Boolean, color: Color) {
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    val top = Offset(center.x, 0f)
    val bottom = Offset(center.x, size.height)
    val tip = if (up) top else bottom
    val wing = size.width * 0.35f
    val back = if (up) wing else -wing
    drawLine(color, top, bottom, strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(color, tip, Offset(tip.x - wing, tip.y + back), strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(color, tip, Offset(tip.x + wing, tip.y + back), strokeWidth = stroke.width, cap = StrokeCap.Round)
}

private fun DrawScope.drawCross(color: Color) {
    val width = 2.dp.toPx()
    drawLine(color, Offset.Zero, Offset(size.width, size.height), strokeWidth = width, cap = StrokeCap.Round)
    drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = width, cap = StrokeCap.Round)
}

private fun previewTransfers() = TransfersUi(
    listOf(
        TransferUi(
            key = TransferKey(TransferDirection.SENDING, "1"),
            name = "Walrus.mp4",
            status = TransferStatus.ACTIVE,
            fraction = 0.42f,
            detail = "34 MB of 80 MB · 4.1 MB/s · 12 s left",
            percent = "42%",
            speed = "4.1 MB/s",
        ),
        TransferUi(
            key = TransferKey(TransferDirection.SENDING, "2"),
            name = "Beach day.mp4",
            status = TransferStatus.QUEUED,
            fraction = 0f,
            detail = "Waiting · 120 MB",
            percent = "",
        ),
        TransferUi(
            key = TransferKey(TransferDirection.RECEIVING, "7"),
            name = "Q4 Report.pdf",
            status = TransferStatus.DONE,
            fraction = 1f,
            detail = "Received · 16 KB",
            percent = "",
        ),
    ),
)

@Preview
@Composable
private fun TransfersPillPreview() {
    AirTheme { TransfersPill(transfers = ::previewTransfers, onClick = {}) }
}

@Preview
@Composable
private fun TransfersListPreview() {
    AirTheme {
        Box(Modifier.background(AirColors.Surface)) {
            TransfersList(previewTransfers(), onCancel = {}, onCancelAll = {}, onClearFinished = {})
        }
    }
}
