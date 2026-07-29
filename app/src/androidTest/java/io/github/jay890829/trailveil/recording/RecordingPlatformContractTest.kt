package io.github.jay890829.trailveil.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingPlatformContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestDeclaresOnlyForegroundLocationCapabilitiesAndPrivateService() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES).toLong(),
            ),
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in permissions)
        assertFalse(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertFalse(Manifest.permission.WAKE_LOCK in permissions)

        val service = context.packageManager.getServiceInfo(
            ComponentName(context, RecordingForegroundService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertFalse(service.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        assertEquals(0, service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
    }

    @Test
    fun channelIsLowImportanceAndStopActionIsImmutableAndSessionScoped() {
        val notifier = RecordingForegroundNotifier(context)
        notifier.ensureChannel()
        val channel = requireNotNull(
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(RecordingForegroundNotifier.CHANNEL_ID),
        )
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

        val first = notifier.notification(sessionId = 41L)
        val second = notifier.notification(sessionId = 42L)
        assertTrue(first.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, first.actions.size)
        assertTrue(first.actions.single().actionIntent.isImmutable)
        assertEquals(context.packageName, first.actions.single().actionIntent.creatorPackage)
        assertNotEquals(
            first.actions.single().actionIntent,
            second.actions.single().actionIntent,
        )
    }
}
