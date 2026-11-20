package com.kotlistine.midair.core.transfer.domain

import com.kotlistine.midair.core.domain.util.DataError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

/** Which way a file moves. */
enum class TransferDirection { SENDING, RECEIVING }

/** A transfer's key: an item id, which is only unique per direction (each phone numbers its own items). */
data class TransferKey(val direction: TransferDirection, val itemId: String)

/** Where one file's transfer stands. */
enum class TransferStatus {
    /** Waiting for its turn: files go one at a time, so each goes at the link's full speed. */
    QUEUED,

    /** Its bytes are moving. */
    ACTIVE,

    /** All of it is here; it is being written to its final place. */
    SAVING,
    DONE,
    FAILED,
    CANCELLED,
    ;

    val isFinished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/** One file moving to or from the paired device. */
data class Transfer(
    val key: TransferKey,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: TransferStatus = TransferStatus.QUEUED,
    val bytesDone: Long = 0L,
    /** Smoothed; 0 until there are two readings to compare. */
    val bytesPerSecond: Long = 0L,
    /** Why it failed, when it did. */
    val error: DataError? = null,
) {
    /** 0..1; 0 for an empty file that hasn't finished. */
    val fraction: Float
        get() = when {
            status == TransferStatus.DONE || status == TransferStatus.SAVING -> 1f
            sizeBytes <= 0L -> 0f
            else -> (bytesDone.toDouble() / sizeBytes).toFloat().coerceIn(0f, 1f)
        }

    /** Seconds left at the current speed, or null when unknown. */
    val secondsLeft: Long?
        get() {
            if (status != TransferStatus.ACTIVE || bytesPerSecond <= 0L) return null
            return ((sizeBytes - bytesDone).coerceAtLeast(0L) + bytesPerSecond - 1) / bytesPerSecond
        }
}

/** How a transfer is getting on, reported by whoever moves its bytes. Calls may come from any thread. */
interface TransferProgress {
    /** It waits for another file to finish first. */
    fun queued() {}

    /** [bytesDone] of the file have moved so far. */
    fun progressed(bytesDone: Long) {}

    /** All bytes are here; the file is being written to its final place. */
    fun saving() {}

    companion object {
        val None: TransferProgress = object : TransferProgress {}
    }
}

/** Progress readings closer together than this only update the board when the whole percent changes. */
private const val MinReportMillis = 250L

/** How quickly the speed follows a change (0..1 per reading, weighted by time). */
private const val SpeedSmoothingMillis = 1_500.0

/**
 * Every file moving to or from the paired device, with its own progress and speed, for the screens and the
 * notification to show and to stop one by one. App-wide: transfers outlive any one screen.
 *
 * Thread-safe. Progress is coalesced here (at most a few updates a second per file), so a fast link reporting every
 * few kilobytes doesn't flood whoever watches [transfers].
 */
class TransferBoard(private val nowMillis: () -> Long = monotonicMillis()) {

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())

    /** Oldest first; finished ones stay until [clearFinished] (or a new transfer with the same key replaces them). */
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val cancels = MutableStateFlow<Map<TransferKey, () -> Unit>>(emptyMap())
    private val readings = MutableStateFlow<Map<TransferKey, Reading>>(emptyMap())

    /** Puts a new transfer on the board, queued. [onCancel] stops it (see [cancel]). Returns its progress reporter. */
    fun add(
        key: TransferKey,
        name: String,
        mimeType: String,
        sizeBytes: Long,
        onCancel: () -> Unit,
    ): TransferProgress {
        cancels.update { it + (key to onCancel) }
        readings.update { it - key }
        _transfers.update { list ->
            list.filterNot { it.key == key } + Transfer(key, name, mimeType, sizeBytes.coerceAtLeast(0L))
        }
        return Reporter(key)
    }

    /** The transfer ended; [error] says why when it didn't succeed ([DataError.Network.CANCELLED]: it was stopped). */
    fun finish(key: TransferKey, error: DataError? = null) {
        cancels.update { it - key }
        readings.update { it - key }
        val status = when (error) {
            null -> TransferStatus.DONE
            DataError.Network.CANCELLED -> TransferStatus.CANCELLED
            else -> TransferStatus.FAILED
        }
        change(key) { transfer ->
            if (transfer.status.isFinished) {
                transfer
            } else {
                transfer.copy(
                    status = status,
                    bytesDone = if (status == TransferStatus.DONE) transfer.sizeBytes else transfer.bytesDone,
                    bytesPerSecond = 0L,
                    error = error.takeIf { status == TransferStatus.FAILED },
                )
            }
        }
    }

    /** Stops the transfer; it shows as cancelled once whoever moves it has stopped ([finish]). */
    fun cancel(key: TransferKey) {
        cancels.value[key]?.invoke()
    }

    /** Stops every transfer still under way. */
    fun cancelAll() {
        cancels.value.values.forEach { it() }
    }

    /** Takes finished transfers off the board. */
    fun clearFinished() {
        _transfers.update { list -> list.filterNot { it.status.isFinished } }
    }

    /** Takes one finished transfer off the board (a running one stays). */
    fun dismiss(key: TransferKey) {
        _transfers.update { list -> list.filterNot { it.key == key && it.status.isFinished } }
    }

    private fun change(key: TransferKey, block: (Transfer) -> Transfer) {
        _transfers.update { list ->
            val index = list.indexOfFirst { it.key == key }
            if (index < 0) return@update list
            val old = list[index]
            val new = block(old)
            if (new == old) list else list.toMutableList().apply { set(index, new) }
        }
    }

    private fun onProgress(key: TransferKey, bytesDone: Long) {
        val now = nowMillis()
        var speed = 0L
        var report = false
        readings.update { all ->
            // The block may run again when another thread got in first: start each run afresh.
            speed = 0L
            report = false
            val last = all[key]
            if (last == null) {
                report = true
                return@update all + (key to Reading(now, bytesDone, 0.0, now))
            }
            val elapsed = now - last.atMillis
            if (bytesDone <= last.bytes || elapsed <= 0L) return@update all
            val sample = (bytesDone - last.bytes) * 1000.0 / elapsed
            val weight = 1.0 - kotlin.math.exp(-elapsed / SpeedSmoothingMillis)
            val smoothed = if (last.speed == 0.0) sample else last.speed + (sample - last.speed) * weight
            speed = smoothed.toLong()
            report = now - last.reportedAtMillis >= MinReportMillis
            all + (key to Reading(now, bytesDone, smoothed, if (report) now else last.reportedAtMillis))
        }
        change(key) { transfer ->
            if (transfer.status.isFinished || transfer.status == TransferStatus.SAVING) return@change transfer
            val percentChanged = percentOf(bytesDone, transfer.sizeBytes) != percentOf(transfer.bytesDone, transfer.sizeBytes)
            if (!report && !percentChanged && transfer.status == TransferStatus.ACTIVE) return@change transfer
            transfer.copy(
                status = TransferStatus.ACTIVE,
                bytesDone = bytesDone.coerceAtLeast(0L),
                bytesPerSecond = if (speed > 0L) speed else transfer.bytesPerSecond,
            )
        }
    }

    private inner class Reporter(private val key: TransferKey) : TransferProgress {
        override fun queued() = change(key) { transfer ->
            if (transfer.status == TransferStatus.ACTIVE || transfer.status.isFinished) {
                transfer
            } else {
                transfer.copy(status = TransferStatus.QUEUED)
            }
        }

        override fun progressed(bytesDone: Long) = onProgress(key, bytesDone)

        override fun saving() = change(key) { transfer ->
            if (transfer.status.isFinished) {
                transfer
            } else {
                transfer.copy(status = TransferStatus.SAVING, bytesDone = transfer.sizeBytes, bytesPerSecond = 0L)
            }
        }
    }

    /** The last reading of one transfer, for its speed. */
    private class Reading(val atMillis: Long, val bytes: Long, val speed: Double, val reportedAtMillis: Long)
}

private fun percentOf(bytes: Long, size: Long): Int = if (size <= 0L) 0 else (bytes * 100 / size).toInt()

private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
