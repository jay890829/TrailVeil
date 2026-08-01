package app.trailveil.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.trailveil.MainActivity
import app.trailveil.R

/** Builds the mandatory foreground notification without depending on notification permission state. */
internal class RecordingForegroundNotifier(
    private val context: Context,
) {
    fun show(service: Service, sessionId: Long?) {
        ensureChannel()
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            notification(sessionId),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    fun dismiss(service: Service) {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    fun ensureChannel() {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.recording_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.recording_notification_text)
                setShowBadge(false)
            },
        )
    }

    internal fun notification(sessionId: Long?): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ACTION_STOP
            putExtra(RecordingForegroundService.EXTRA_SESSION_ID, sessionId ?: NO_SESSION_ID)
            data = "trailveil://recording/stop/${sessionId ?: NO_SESSION_ID}".toUri()
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording_notification)
            .setContentTitle(context.getString(R.string.recording_notification_title))
            .setContentText(context.getString(R.string.recording_notification_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_recording_notification,
                context.getString(R.string.recording_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    internal companion object {
        const val CHANNEL_ID = "trailveil.recording"
        const val NOTIFICATION_ID = 1001
        const val NO_SESSION_ID = -1L
        private const val CONTENT_REQUEST_CODE = 1001
        private const val STOP_REQUEST_CODE = 1002
    }
}