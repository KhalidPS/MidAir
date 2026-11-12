package com.kotlistine.transferingfilebyair.feature.shelf.presentation.model

import androidx.compose.runtime.Immutable
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transfer
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferDirection
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKey
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferStatus

/** One file moving to or from the paired device, as the transfers list and the arriving cards show it. */
@Immutable
data class TransferUi(
    val key: TransferKey,
    val name: String,
    val status: TransferStatus,
    /** 0..1. */
    val fraction: Float,
    /** "Waiting", "12 MB of 80 MB · 4.1 MB/s · 15 s left", "Saving", "Sent", "Couldn't send"… */
    val detail: String,
    /** "42%" while it moves, else "". */
    val percent: String,
    /** "4.1 MB/s" while it moves and the speed is known, else "". */
    val speed: String = "",
) {
    val isSending: Boolean get() = key.direction == TransferDirection.SENDING
    val isFinished: Boolean get() = status.isFinished
}

/** Every transfer, and what the list's header and the notification-like pill show about them. */
@Immutable
data class TransfersUi(
    val transfers: List<TransferUi> = emptyList(),
) {
    val active: List<TransferUi> get() = transfers.filterNot { it.isFinished }
    val hasFinished: Boolean get() = transfers.any { it.isFinished }

    /** All bytes of the running transfers together, 0..1. */
    val overallFraction: Float = run {
        val running = transfers.filterNot { it.isFinished }
        if (running.isEmpty()) 1f else running.sumOf { it.fraction.toDouble() }.toFloat() / running.size
    }

    /** The receiving transfer of the peer's card [peerItemId], if there is one. */
    fun receiving(peerItemId: String): TransferUi? =
        transfers.lastOrNull { it.key.direction == TransferDirection.RECEIVING && it.key.itemId == peerItemId }
}

internal fun Transfer.toUi(): TransferUi = TransferUi(
    key = key,
    name = name,
    status = status,
    fraction = fraction,
    detail = detail(),
    percent = if (status == TransferStatus.ACTIVE) "${(fraction * 100).toInt()}%" else "",
    speed = if (status == TransferStatus.ACTIVE && bytesPerSecond > 0L) "${formatFileSize(bytesPerSecond)}/s" else "",
)

private fun Transfer.detail(): String = when (status) {
    TransferStatus.QUEUED -> "Waiting · ${formatFileSize(sizeBytes)}"
    TransferStatus.ACTIVE -> buildString {
        append(formatFileSize(bytesDone)).append(" of ").append(formatFileSize(sizeBytes))
        if (bytesPerSecond > 0L) append(" · ").append(formatFileSize(bytesPerSecond)).append("/s")
        secondsLeft?.let { append(" · ").append(formatDuration(it)).append(" left") }
    }
    TransferStatus.SAVING -> "Saving · ${formatFileSize(sizeBytes)}"
    TransferStatus.DONE ->
        "${if (key.direction == TransferDirection.SENDING) "Sent" else "Received"} · ${formatFileSize(sizeBytes)}"
    TransferStatus.CANCELLED -> "Stopped"
    TransferStatus.FAILED -> if (key.direction == TransferDirection.SENDING) "Couldn't send" else "Couldn't receive"
}

/** "8 s", "2 min", "1 h 5 min". */
internal fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3_600 -> "${(seconds + 30) / 60} min"
    else -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
}
