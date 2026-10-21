package com.kotlistine.transferingfilebyair.transfer

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
import androidx.core.app.ServiceCompat
import com.kotlistine.transferingfilebyair.MainActivity
import com.kotlistine.transferingfilebyair.core.transfer.domain.TransferKeepAlive

private const val ChannelId = "transfers"
private const val NotificationId = 1
private const val ExtraDescription = "description"

/**
 * A foreground service that does no work of its own: while it runs, Android keeps the app alive, so a file keeps
 * moving after the user leaves the app or locks the phone. Its notification says what is moving.
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val moving = intent?.getStringExtra(ExtraDescription) ?: "Moving a file"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(ChannelId, "File transfers", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a file moves to or from the phone next to you"
                },
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(moving)
            .setContentText("Keep the phones close until it's done.")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NotificationId, notification, type)
        return START_NOT_STICKY
    }

    // Android 15 ends long data-sync services; a transfer that outlives that has failed anyway.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

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

/** Runs [TransferService] while at least one transfer holds it; the latest description is shown. */
class ServiceTransferKeepAlive(context: Context) : TransferKeepAlive {
    private val context = context.applicationContext
    private var holds = 0

    @Synchronized
    override fun hold(description: String): () -> Unit {
        holds++
        // Starting the service from the background isn't allowed on newer Android; the transfer still runs then,
        // just without the extra protection.
        runCatching { TransferService.start(context, description) }
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
