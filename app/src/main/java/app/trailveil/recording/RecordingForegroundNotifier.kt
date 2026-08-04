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

    /**
     * Tells the user their exploration was saved.
     *
     * A recording can end while the app is not on screen — that is the whole point of stopping from
     * the notification — so the confirmation cannot live only in the app. Posting is best-effort:
     * the exploration is already durable by the time this runs, and nothing about it may depend on
     * whether a notification could be shown.
     */
    fun showCompleted() {
        try {
            ensureChannel()
            requireNotNull(context.getSystemService(NotificationManager::class.java))
                .notify(COMPLETED_NOTIFICATION_ID, completedNotification())
        } catch (_: RuntimeException) {
            // Notifications may be denied or disabled entirely. The exploration is saved either way.
        }
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
        // Its own channel, so muting "an exploration is being recorded" does not also mute
        // "your exploration was saved", which are opposite things to want.
        manager.createNotificationChannel(
            NotificationChannel(
                COMPLETED_CHANNEL_ID,
                context.getString(R.string.recording_completed_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.recording_completed_text)
                setShowBadge(false)
            },
        )
    }

    internal fun completedNotification(): Notification = NotificationCompat
        .Builder(context, COMPLETED_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_recording_notification)
        .setContentTitle(context.getString(R.string.recording_completed_title))
        .setContentText(context.getString(R.string.recording_completed_text))
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setAutoCancel(true)
        .setOngoing(false)
        .setContentIntent(openAppIntent())
        .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        CONTENT_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun notification(sessionId: Long?): Notification {
        val contentIntent = openAppIntent()
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
        const val COMPLETED_CHANNEL_ID = "trailveil.recording.completed"
        const val NOTIFICATION_ID = 1001
        const val COMPLETED_NOTIFICATION_ID = 1002
        const val NO_SESSION_ID = -1L
        private const val CONTENT_REQUEST_CODE = 1001
        private const val STOP_REQUEST_CODE = 1002
    }
}