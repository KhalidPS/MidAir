package com.kotlistine.transferingfilebyair.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideMessage
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transfer
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferDirection
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferStatus
import com.sun.management.GarbageCollectionNotificationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData
import kotlin.math.ceil
import kotlin.time.TimeSource

/** A move later than this (beyond the slide's quickest) is past what the card's playback absorbs: it coasts, then corrects. */
private const val PlaybackLimitMillis = 150.0

/** Worth a line of its own. */
private const val LateMoveMillis = 60.0
private const val ArrivalGapMillis = 50.0
private const val SlowFrameMillis = 25.0

/** How long after the drop the laptop is still watched: the landing, the file coming in, the shelf taking it. */
private const val AfterDropMillis = 4_000L
private const val AfterFileMillis = 1_000L

/** Lines of detail per slide, so a bad one doesn't flood the console; the summary counts everything. */
private const val MaxDetailLines = 40

/**
 * A diagnostic, on the laptop only: for every card the phone slides over, how evenly its moves came in over the network,
 * which frames this window drew late, and what the JVM did meanwhile (garbage collection pauses, code still being
 * compiled). Printed to the console and appended to `slide-timings.log` in the app's data folder.
 *
 * It only reads: the moves as they come off the socket, the shelf's transfer board, and the frame clock while a slide
 * is on.
 */
internal class SlideTimings(private val file: File) {
    private val origin = TimeSource.Monotonic.markNow()
    private val lock = Any()
    private val clockText = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** While true, the window reports every frame. */
    val isWatching = MutableStateFlow(false)

    private var slideNumber = 0
    private var current: Slide? = null
    private var endJob: Job? = null

    /** Watches the messages (as they arrive, on the socket's thread), the transfers, and the JVM's collections. */
    fun start(messages: Flow<SlideMessage>, transfers: Flow<List<Transfer>>?) {
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch {
            messages.collect(::onMessage)
        }
        transfers?.let { flow -> scope.launch { flow.collect(::onTransfers) } }
        watchCollections()
        write("— slide timings on; written to ${file.absolutePath}")
    }

    private fun now(): Double = origin.elapsedNow().inWholeMicroseconds / 1000.0

    private fun onMessage(message: SlideMessage) {
        val at = now()
        synchronized(lock) {
            when (message) {
                is SlideMessage.DragStart -> begin(message.itemId, at)
                is SlideMessage.DragMove -> current?.onMove(message, at, batchSize = 1)
                is SlideMessage.DragMoves -> message.moves.forEach { current?.onMove(it, at, batchSize = message.moves.size) }
                is SlideMessage.DragCommit -> current?.takeIf { it.itemId == message.itemId }?.let { slide ->
                    slide.droppedAt = at
                    slide.detail(at, "dropped (commit)")
                    finishIn(AfterDropMillis)
                }
                is SlideMessage.DragCancel -> current?.takeIf { it.itemId == message.itemId }?.let { slide ->
                    slide.detail(at, "pulled back (cancel)")
                    slide.outcome = "pulled back"
                    finishIn(AfterFileMillis)
                }
                is SlideMessage.Pong -> current?.let { slide ->
                    val roundTrip = System.currentTimeMillis() - message.pingSentAtMillis
                    if (roundTrip in 0..10_000) slide.roundTrips += roundTrip
                }
                else -> current?.detail(at, message::class.simpleName.orEmpty())
            }
        }
    }

    private fun begin(itemId: String, at: Double) {
        current?.let { finish(it, "replaced by the next slide") }
        slideNumber++
        current = Slide(slideNumber, itemId, at, compiledMillis())
        endJob?.cancel()
        isWatching.value = true
    }

    private fun onTransfers(transfers: List<Transfer>) {
        val at = now()
        synchronized(lock) {
            val slide = current ?: return
            val transfer = transfers.firstOrNull {
                it.key.direction == TransferDirection.RECEIVING && it.key.itemId == slide.itemId
            } ?: return
            if (transfer.status == slide.fileStatus) return
            slide.fileStatus = transfer.status
            slide.detail(at, "file ${transfer.status.name.lowercase()} (${transfer.sizeBytes / 1024} KB)")
            when (transfer.status) {
                TransferStatus.ACTIVE -> slide.fileStartedAt = at
                TransferStatus.DONE, TransferStatus.FAILED, TransferStatus.CANCELLED -> {
                    slide.fileEndedAt = at
                    slide.outcome = "file ${transfer.status.name.lowercase()}"
                    finishIn(AfterFileMillis)
                }
                else -> Unit
            }
        }
    }

    /** One frame of the window, [intervalMillis] after the one before. */
    fun onFrame(intervalMillis: Double) {
        val at = now()
        synchronized(lock) {
            val slide = current ?: return
            val phase = slide.phaseAt(at)
            slide.frames.getOrPut(phase) { ArrayList() } += intervalMillis
            if (intervalMillis >= SlowFrameMillis) slide.detail(at, "slow frame ${intervalMillis.f()} ms ($phase)")
        }
    }

    private fun onCollection(name: String, action: String, durationMillis: Long) {
        val at = now()
        synchronized(lock) {
            val slide = current ?: return
            slide.collections += durationMillis
            slide.detail(at, "GC $name ($action) $durationMillis ms (${slide.phaseAt(at)})")
        }
    }

    private fun finishIn(millis: Long) {
        endJob?.cancel()
        val slide = current
        endJob = scope.launch {
            delay(millis)
            synchronized(lock) { if (current === slide && slide != null) finish(slide, slide.outcome) }
        }
    }

    /** Under [lock]. */
    private fun finish(slide: Slide, outcome: String?) {
        current = null
        isWatching.value = false
        write(slide.report(outcome, compiledMillis()))
    }

    private fun watchCollections() {
        ManagementFactory.getGarbageCollectorMXBeans().forEach { bean ->
            (bean as? NotificationEmitter)?.addNotificationListener({ notification, _ ->
                if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) return@addNotificationListener
                val info = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
                // A concurrent cycle runs beside the app; only the pauses stop it.
                if ("Concurrent" in info.gcName) return@addNotificationListener
                onCollection(info.gcName, info.gcAction, info.gcInfo.duration)
            }, null, null)
        }
    }

    /** How long the JIT has spent compiling so far: code it hasn't compiled yet runs many times slower. */
    private fun compiledMillis(): Long? = ManagementFactory.getCompilationMXBean()
        ?.takeIf { it.isCompilationTimeMonitoringSupported }
        ?.totalCompilationTime

    private fun write(text: String) {
        val stamped = "${LocalTime.now().format(clockText)}  $text"
        println(stamped)
        runCatching { file.appendText(stamped + "\n") }
    }

    private inner class Slide(val number: Int, val itemId: String, val startedAt: Double, val compiledAtStart: Long?) {
        var droppedAt: Double? = null
        var fileStartedAt: Double? = null
        var fileEndedAt: Double? = null
        var fileStatus: TransferStatus? = null
        var outcome: String? = null

        var moves = 0
        var batchedMoves = 0
        private var lastSeq = -1
        private var lastArrival: Double? = null
        private var lastSent: Long? = null
        private var quickest = Double.POSITIVE_INFINITY
        private val transits = ArrayList<Double>()
        val arrivalGaps = ArrayList<Double>()
        val sendGaps = ArrayList<Double>()
        val roundTrips = ArrayList<Long>()
        val collections = ArrayList<Long>()
        val frames = LinkedHashMap<String, ArrayList<Double>>()
        private val details = ArrayList<String>()
        private var droppedDetails = 0

        fun phaseAt(at: Double): String = when {
            droppedAt == null -> "sliding"
            fileStartedAt == null -> "landing"
            fileEndedAt == null -> "file coming in"
            else -> "file kept"
        }

        fun onMove(move: SlideMessage.DragMove, at: Double, batchSize: Int) {
            if (move.itemId != itemId) return
            if (move.seq <= lastSeq) detail(at, "move ${move.seq} out of order (after ${lastSeq})")
            lastSeq = maxOf(lastSeq, move.seq)
            moves++
            if (batchSize > 1) batchedMoves++
            lastArrival?.let { previous ->
                val gap = at - previous
                // A batch's moves all come in together: only its first one says how long the wait was.
                if (gap > 0.0 || batchSize == 1) arrivalGaps += gap
                if (gap >= ArrivalGapMillis) detail(at, "no move for ${gap.f()} ms")
            }
            lastArrival = at
            if (move.atMillis > 0L) {
                lastSent?.let { sendGaps += (move.atMillis - it).toDouble() }
                lastSent = move.atMillis
                val transit = at - move.atMillis
                if (transit < quickest) quickest = transit
                transits += transit
                val late = transit - quickest
                if (late >= LateMoveMillis) detail(at, "move ${move.seq} ${late.f()} ms late")
            }
        }

        fun detail(at: Double, text: String) {
            if (details.size >= MaxDetailLines) {
                droppedDetails++
                return
            }
            details += "  +${(at - startedAt).toLong().toString().padStart(5)} ms  $text"
        }

        fun report(outcome: String?, compiledAtEnd: Long?): String = buildString {
            appendLine("── slide $number ($itemId) ${outcome ?: "ended"} ──")
            details.forEach(::appendLine)
            if (droppedDetails > 0) appendLine("  … $droppedDetails more lines")

            val lateness = transits.map { it - quickest }
            append("  network : $moves moves")
            if (batchedMoves > 0) append(", $batchedMoves of them sent in batches (the phone's link was busy)")
            appendLine()
            if (sendGaps.isNotEmpty()) {
                appendLine("            phone sent every ${sendGaps.median().f()} ms (longest pause ${sendGaps.max().f()} ms)")
            }
            if (arrivalGaps.isNotEmpty()) {
                appendLine(
                    "            arrived every ${arrivalGaps.median().f()} ms (longest wait ${arrivalGaps.max().f()} ms, " +
                        "${arrivalGaps.count { it >= ArrivalGapMillis }} waits ≥ ${ArrivalGapMillis.toInt()} ms)",
                )
            }
            if (lateness.isNotEmpty()) {
                appendLine(
                    "            late by p50 ${lateness.median().f()} / p95 ${lateness.p95().f()} / max ${lateness.max().f()} ms; " +
                        "${lateness.count { it > PlaybackLimitMillis }} past the ${PlaybackLimitMillis.toInt()} ms the card absorbs",
                )
            }
            if (roundTrips.isNotEmpty()) appendLine("            round trip ${roundTrips.joinToString(", ")} ms")

            frames.forEach { (phase, intervals) ->
                val slow = intervals.filter { it >= SlowFrameMillis }
                appendLine(
                    "  frames  : $phase: ${intervals.size} frames, usually ${intervals.median().f()} ms, " +
                        "${slow.size} slow (worst ${intervals.max().f()} ms)",
                )
            }
            val compiled = if (compiledAtStart != null && compiledAtEnd != null) compiledAtEnd - compiledAtStart else null
            append("  JVM     : GC pauses ${collections.size} (${collections.sum()} ms)")
            if (compiled != null) append(", JIT compiled for $compiled ms (high = code still warming up)")
            fileStartedAt?.let { started ->
                droppedAt?.let { append("\n  file    : started ${(started - it).toLong()} ms after the drop") }
                fileEndedAt?.let { append(", took ${(it - started).toLong()} ms") }
            }
        }
    }
}

/** Reports the window's frame intervals to [timings] while a slide is on (the rest of the time it costs nothing). */
@Composable
internal fun SlideFrameWatch(timings: SlideTimings) {
    LaunchedEffect(timings) {
        timings.isWatching.collectLatest { watching ->
            if (!watching) return@collectLatest
            var last = withFrameNanos { it }
            while (true) {
                val frame = withFrameNanos { it }
                timings.onFrame((frame - last) / 1_000_000.0)
                last = frame
            }
        }
    }
}

private fun List<Double>.median(): Double = sorted()[size / 2]

private fun List<Double>.p95(): Double = sorted()[(ceil(0.95 * size).toInt() - 1).coerceIn(0, lastIndex)]

private fun Double.f(): String = if (this >= 100) toLong().toString() else "%.1f".format(this)
