package com.wedfaces.cameraoperator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

internal object TransferNotifications {
    const val CHANNEL_ID = "transfer_progress"
    const val CAMERA_NOTIFICATION_ID = 2401

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo transfers",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for camera downloads and WED Faces uploads."
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun workerForegroundInfo(
        context: Context,
        batchId: String,
        completed: Int,
        total: Int,
    ): ForegroundInfo {
        val notification = transferNotification(
            context = context,
            batchId = batchId,
            title = "WED Faces upload",
            text = "Uploading $completed of $total",
            includeActions = true,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(batchId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId(batchId), notification)
        }
    }

    fun cameraNotification(context: Context, batchId: String, completed: Int, total: Int): Notification =
        transferNotification(
            context = context,
            batchId = batchId,
            title = "WED Faces camera download",
            text = "Downloading $completed of $total",
            includeActions = false,
        )

    private fun transferNotification(
        context: Context,
        batchId: String,
        title: String,
        text: String,
        includeActions: Boolean,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            notificationId(batchId) + 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wed_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(includeActions)
            .setProgress(total.coerceAtLeast(1), completed.coerceIn(0, total.coerceAtLeast(1)), false)
            .apply {
                if (includeActions) {
                    addAction(
                        0,
                        "Pause",
                        actionIntent(context, batchId, TransferActionReceiver.ACTION_PAUSE),
                    )
                    addAction(
                        0,
                        "Cancel",
                        actionIntent(context, batchId, TransferActionReceiver.ACTION_CANCEL),
                    )
                }
            }
            .build()
    }

    private fun actionIntent(context: Context, batchId: String, action: String) =
        PendingIntent.getBroadcast(
            context,
            "$batchId:$action".hashCode(),
            Intent(context, TransferActionReceiver::class.java)
                .setAction(action)
                .putExtra(TransferActionReceiver.KEY_BATCH_ID, batchId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationId(batchId: String) = 2500 + (batchId.hashCode().ushr(1) % 10000)
}
