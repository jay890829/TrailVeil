package app.trailveil.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingPlatformContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestDeclaresExactlyTheLocationCapabilitiesPlanNamesAndAPrivateService() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES).toLong(),
            ),
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        // Set-equality, so the name's "exactly" is what the assertion holds: any permission added
        // to or removed from the manifest fails here by name, not only the two the old denylist
        // happened to watch. ACCESS_BACKGROUND_LOCATION is P4-041's: declared so the
        // Allow-all-the-time grade exists, letting a sticky restart re-arm location from the
        // background (measured: refused at While-in-use, recovered at all-the-time on the reference
        // device). PLAN's privacy section names its single purpose; the app never prompts for it,
        // and recording still starts only from a visible activity. This test fired on the
        // declaration exactly as designed - the posture changed with a recorded PLAN entry rather
        // than silently.
        assertEquals(
            setOf(
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                // Injected by the build toolchain for apps registering non-exported dynamic
                // receivers on targetSdk 34+; not declared in our manifest. Listed so the equality
                // stays strict - if the toolchain stops injecting it, this fires and says why.
                "app.trailveil.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ).filterTo(sortedSetOf()) { true },
            permissions.toSortedSet(),
        )

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

    /**
     * An outcome has to reach a user who stopped from the shade, or who never noticed the recording
     * die at all, so it is a normal dismissible notification on its own channel rather than another
     * ongoing one.
     */
    @Test
    fun outcomeNotificationsAreDismissibleAndSeparatelyMutable() {
        val notifier = RecordingForegroundNotifier(context)
        notifier.ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = requireNotNull(
            manager.getNotificationChannel(RecordingForegroundNotifier.OUTCOME_CHANNEL_ID),
        )
        assertNotEquals(RecordingForegroundNotifier.CHANNEL_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(
            manager.getNotificationChannel(
                RecordingForegroundNotifier.RETIRED_COMPLETED_CHANNEL_ID,
            ),
        )

        listOf(
            notifier.outcomeNotification(
                R.string.recording_completed_title,
                R.string.recording_completed_text,
            ),
            notifier.outcomeNotification(
                R.string.recording_interrupted_title,
                R.string.recording_interrupted_text,
            ),
        ).forEach { outcome ->
            assertEquals(0, outcome.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(outcome.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertNull(outcome.actions)
        }
        // One session has one outcome, so the two share an id and the later one replaces the older.
        assertNotEquals(
            RecordingForegroundNotifier.NOTIFICATION_ID,
            RecordingForegroundNotifier.OUTCOME_NOTIFICATION_ID,
        )
    }
}
