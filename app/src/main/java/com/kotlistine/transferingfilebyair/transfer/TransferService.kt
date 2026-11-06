package com.kotlistine.transferingfilebyair.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.kotlistine.transferingfilebyair.MainActivity
import com.kotlistine.transferingfilebyair.core.transfer.domain.Transfer
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferBoard
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferDirection
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKey
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale

private const val ChannelId = "transfers"
private const val DoneChannelId = "transfers_done"
private const val GroupKey = "com.kotlistine.transferingfilebyair.TRANSFERS"

/** The service's own notification: the summary of everything moving. */
private const val NotificationId = 1

/** What happened once everything stopped moving. */
private const val DoneNotificationId = 2

private const val ExtraDescription = "description"
private const val ActionStopAll = "com.kotlistine.transferingfilebyair.STOP_ALL"
private const val ActionStopOne = "com.kotlistine.transferingfilebyair.STOP_ONE"
private const val ExtraDirection = "direction"
private const val ExtraItemId = "itemId"

/**
 * Android drops a notification update when an app posts more than about five a second, so the notifications are
 * redrawn at most this often, whatever the files' own progress does.
 */
private const val RefreshMillis = 1_000L

/** Files with a notification of their own, at most: more would only push the others off the shade. */
private const val MaxFileNotifications = 4

/**
 * Refreshes in a row with nothing moving before the service stops by itself. Normally whoever moves the files stops it;
 * this only covers a service started with nothing to do (a "Stop" tapped on a notification left over from before).
 */
private const val IdleRefreshesBeforeStop = 3

/**
 * A foreground service: while it runs, Android keeps the app alive, so files keep moving after the user leaves the app
 * or locks the phone. It shows what moves, from the app-wide [TransferBoard]: one summary (everything together, with
 * "Stop all") and one notification per file (its own progress bar and "Stop"), like any file-sharing app. Once nothing
 * moves any more, it says what happened ("3 files received").
 */
class TransferService : Service() {

    private val board: TransferBoard by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { NotificationManagerCompat.from(this) }

    /** Every transfer seen while this service ran, for the closing summary. */
    private val seen = mutableSetOf<TransferKey>()

    /** File notifications currently posted, by notification id. */
    private val posted = mutableSetOf<Int>()
    private var lastSummary: SummaryModel? = null
    private var isRefreshing = false
    private var idleRefreshes = 0
    private var lastFiles: Map<Int, FileModel> = emptyMap()
    private var fallbackDescription = "Moving a file"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStopAll -> board.cancelAll()
            ActionStopOne -> intent.transferKey()?.let(board::cancel)
            else -> intent?.getStringExtra(ExtraDescription)?.let { fallbackDescription = it }
        }
        // Within moments of every start, or Android ends the app: the first picture goes up at once.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NotificationId, summaryNotification(summaryOf(board.transfers.value)), type)
        if (!isRefreshing) {
            isRefreshing = true
            refreshEverySecond()
        }
        return START_NOT_STICKY
    }

    private fun refreshEverySecond() {
        scope.launch {
            while (isActive) {
                render(board.transfers.value)
                delay(RefreshMillis)
            }
        }
    }

    private fun render(transfers: List<Transfer>) {
        val running = transfers.filterNot { it.status.isFinished }
        running.forEach { seen += it.key }
        idleRefreshes = if (running.isEmpty()) idleRefreshes + 1 else 0
        if (idleRefreshes >= IdleRefreshesBeforeStop) {
            stopSelf()
            return
        }
        val summary = summaryOf(transfers)
        if (summary != lastSummary) {
            lastSummary = summary
            notifyQuietly(NotificationId, summaryNotification(summary))
        }
        // The ones moving first, then the ones waiting their turn.
        val shown = running
            .sortedBy { if (it.status == TransferStatus.QUEUED) 1 else 0 }
            .take(MaxFileNotifications)
            .associate { notificationIdOf(it.key) to FileModel.of(it) }
        (posted - shown.keys).forEach { id -> manager.cancel(id) }
        posted.retainAll(shown.keys)
        shown.forEach { (id, model) ->
            if (lastFiles[id] != model) notifyQuietly(id, fileNotification(model))
            posted += id
        }
        lastFiles = shown
    }

    override fun onDestroy() {
        scope.cancel()
        posted.forEach { manager.cancel(it) }
        posted.clear()
        postOutcome(board.transfers.value.filter { it.key in seen && it.status.isFinished })
        super.onDestroy()
    }

    // Android 15 ends long data-sync services; a transfer that outlives that has failed anyway.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    // region Notifications

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(ChannelId, "File transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while files move to or from the phone next to you"
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(DoneChannelId, "Finished transfers", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Says when files have arrived or couldn't be sent"
            },
        )
    }

    private fun summaryNotification(summary: SummaryModel): Notification =
        NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(if (summary.isReceiving) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(summary.title.ifEmpty { fallbackDescription })
            .setContentText(summary.text)
            .setSubText(summary.percent?.let { "$it%" })
            .setProgress(100, summary.percent ?: 0, summary.percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroup(GroupKey)
            .setGroupSummary(true)
            .setContentIntent(openApp())
            .apply { if (summary.canStop) addAction(0, "Stop all", serviceIntent(ActionStopAll, requestCode = 1)) }
            .build()

    private fun fileNotification(file: FileModel): Notification =
        NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(if (file.isSending) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(file.name)
            .setContentText(file.text)
            .setSubText(file.percent?.let { "$it%" })
            .setProgress(100, file.percent ?: 0, file.percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(GroupKey)
            .setContentIntent(openApp())
            .addAction(0, "Stop", stopOne(file.key))
            .build()

    /** What happened to the files of this run, once none moves any more. Nothing for a run where all were stopped. */
    private fun postOutcome(finished: List<Transfer>) {
        val received = finished.count { it.status == TransferStatus.DONE && it.key.direction == TransferDirection.RECEIVING }
        val sent = finished.count { it.status == TransferStatus.DONE && it.key.direction == TransferDirection.SENDING }
        val failed = finished.count { it.status == TransferStatus.FAILED }
        if (received + sent + failed == 0) return
        val title = when {
            received > 0 && sent > 0 -> "${files(received)} received, ${files(sent)} sent"
            received > 0 -> if (received == 1) "Received “${finished.first { it.isDoneReceiving() }.name}”" else "${files(received)} received"
            sent > 0 -> if (sent == 1) "Sent “${finished.first { it.isDoneSending() }.name}”" else "${files(sent)} sent"
            else -> if (failed == 1) "A file didn't make it" else "$failed files didn't make it"
        }
        val text = if (failed > 0 && received + sent > 0) {
            "${files(failed)} didn't make it. Slide again to retry."
        } else if (failed > 0) {
            "The link to the other phone dropped. Slide again to retry."
        } else if (received > 0) {
            "In Downloads, and on your shelf."
        } else {
            "They're on the other phone's shelf."
        }
        val notification = NotificationCompat.Builder(this, DoneChannelId)
            .setSmallIcon(if (received > 0) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notifyQuietly(DoneNotificationId, notification)
    }

    /** Posting needs the notification permission on Android 13+; without it the transfer still runs, unseen. */
    private fun notifyQuietly(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Not allowed to show notifications: nothing to show then.
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** A tap that reaches this service, which puts its notification up again at once (it may have stopped meanwhile). */
    private fun serviceIntent(action: String, requestCode: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(this, TransferService::class.java).setAction(action).apply(extras)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun stopOne(key: TransferKey): PendingIntent =
        serviceIntent(ActionStopOne, requestCode = notificationIdOf(key)) {
            putExtra(ExtraDirection, key.direction.name)
            putExtra(ExtraItemId, key.itemId)
        }

    // endregion

    companion object {
        fun start(context: Context, description: String) {
            val intent = Intent(context, TransferService::class.java).putExtra(ExtraDescription, description)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}

/** The summary notification's content: everything moving together. */
private data class SummaryModel(
    val title: String,
    val text: String,
    /** All files together, 0..100; null while none has started (all waiting). */
    val percent: Int?,
    val isReceiving: Boolean,
    val canStop: Boolean,
)

private fun summaryOf(transfers: List<Transfer>): SummaryModel {
    val running = transfers.filterNot { it.status.isFinished }
    val sending = running.count { it.key.direction == TransferDirection.SENDING }
    val receiving = running.size - sending
    val title = when {
        running.size == 1 -> "${if (sending == 1) "Sending" else "Receiving"} “${running.single().name}”"
        sending > 0 && receiving > 0 -> "Sending ${files(sending)}, receiving ${files(receiving)}"
        sending > 0 -> "Sending ${files(sending)}"
        receiving > 0 -> "Receiving ${files(receiving)}"
        else -> ""
    }
    val total = running.sumOf { it.sizeBytes }
    val done = running.sumOf { if (it.status == TransferStatus.SAVING) it.sizeBytes else it.bytesDone }
    val started = running.any { it.status != TransferStatus.QUEUED }
    val speed = running.sumOf { it.bytesPerSecond }
    val text = buildString {
        if (total > 0) append(formatSize(done)).append(" of ").append(formatSize(total))
        if (speed > 0) append(" · ").append(formatSize(speed)).append("/s")
        if (isEmpty()) append("Keep the phones close until it's done.")
    }
    return SummaryModel(
        title = title,
        text = text,
        percent = if (started && total > 0) (done * 100 / total).toInt() else null,
        isReceiving = receiving > 0,
        canStop = running.isNotEmpty(),
    )
}

/** One file's notification content. */
private data class FileModel(
    val key: TransferKey,
    val name: String,
    val text: String,
    /** 0..100; null while it waits its turn. */
    val percent: Int?,
) {
    val isSending: Boolean get() = key.direction == TransferDirection.SENDING

    companion object {
        fun of(transfer: Transfer): FileModel {
            val text = when (transfer.status) {
                TransferStatus.QUEUED -> "Waiting its turn · ${formatSize(transfer.sizeBytes)}"
                TransferStatus.SAVING -> "Saving · ${formatSize(transfer.sizeBytes)}"
                else -> buildString {
                    append(formatSize(transfer.bytesDone)).append(" of ").append(formatSize(transfer.sizeBytes))
                    if (transfer.bytesPerSecond > 0) append(" · ").append(formatSize(transfer.bytesPerSecond)).append("/s")
                    transfer.secondsLeft?.let { append(" · ").append(formatSeconds(it)).append(" left") }
                }
            }
            val percent = if (transfer.status == TransferStatus.QUEUED) null else (transfer.fraction * 100).toInt()
            return FileModel(transfer.key, transfer.name, text, percent)
        }
    }
}

private fun Transfer.isDoneReceiving() = status == TransferStatus.DONE && key.direction == TransferDirection.RECEIVING

private fun Transfer.isDoneSending() = status == TransferStatus.DONE && key.direction == TransferDirection.SENDING

private fun files(count: Int): String = if (count == 1) "1 file" else "$count files"

/** Stable per transfer, apart from the summary's ids. */
private fun notificationIdOf(key: TransferKey): Int = 1_000 + (key.direction.name + ":" + key.itemId).hashCode().and(0x3FFFFFFF)

private fun Intent.transferKey(): TransferKey? {
    val direction = getStringExtra(ExtraDirection)?.let { name -> TransferDirection.entries.firstOrNull { it.name == name } }
    val itemId = getStringExtra(ExtraItemId)
    return if (direction != null && itemId != null) TransferKey(direction, itemId) else null
}

/** Decimal units, as the app's cards show them. */
private fun formatSize(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${(bytes + 500) / 1_000} KB"
    bytes < 1_000_000_000 -> "${String.format(Locale.ROOT, "%.1f", bytes / 1_000_000.0)} MB"
    else -> "${String.format(Locale.ROOT, "%.2f", bytes / 1_000_000_000.0)} GB"
}

private fun formatSeconds(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3_600 -> "${(seconds + 30) / 60} min"
    else -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
}

/** Runs [TransferService] while at least one transfer holds it; the service itself shows what moves. */
class ServiceTransferKeepAlive(context: Context) : TransferKeepAlive {
    private val context = context.applicationContext
    private var holds = 0

    @Synchronized
    override fun hold(description: String): () -> Unit {
        holds++
        // Starting the service from the background isn't allowed on newer Android; the transfer still runs then,
        // just without the extra protection.
        if (holds == 1) runCatching { TransferService.start(context, description) }
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    released = true
                    holds--
                    if (holds == 0) TransferService.stop(context)
                }
            }
        }
    }
}
