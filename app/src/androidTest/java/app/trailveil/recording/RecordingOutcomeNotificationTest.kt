package app.trailveil.recording

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.TrailVeilApplication
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.data.recording.RecordingOperationId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs in its own class because binding the posted outcome notification needs POST_NOTIFICATIONS
 * granted, while `RecordingForegroundServiceTest`'s premise is that it is denied - and revoking a
 * runtime permission kills the app process mid-suite. Class ordering runs this after the denied
 * tests; the grant leaks only into later classes that do not care about notification permission.
 */
@RunWith(AndroidJUnit4::class)
class RecordingOutcomeNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aCompletedExplorationPostsExactlyOneOutcomeNotification() = runBlocking {
        // The durable completion notification had no binding committed test: deleting the
        // showCompleted() call passed the whole suite. This grants the permission the other
        // service test deliberately runs without, drives a real completion, and counts what the
        // platform actually holds: exactly one outcome notification, on the outcome id, and not
        // the (still present) foreground one.
        enableSystemLocation()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        grant(Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager =
            context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()

        val application = context.applicationContext as TrailVeilApplication
        val repository = application.appContainer.recordingRepository
        // Launch first, then begin: opening the activity with an already-begun session takes the
        // start-continuation path instead of a plain visible-activity start, and standalone in a
        // fresh process that never reaches ACTIVE.
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            val sessionId = repository.beginStart(
                operationId("outcome-begin"),
                System.currentTimeMillis(),
                "instrumentation",
            ).sessionId
            activity.onActivity {
                RecordingForegroundService.startFromVisibleActivity(it, sessionId)
            }
            withTimeout(11_000) {
                while (repository.state().lifecycle != RecordingLifecycle.ACTIVE) {
                    delay(50)
                }
            }

            RecordingForegroundNotifier(context)
                .notification(sessionId)
                .actions
                .single()
                .actionIntent
                .send()
            withTimeout(12_000) {
                while (repository.state().lifecycle != RecordingLifecycle.STOPPED) {
                    delay(50)
                }
            }
            withTimeout(13_000) {
                while (
                    notificationManager.activeNotifications.none {
                        it.id == RecordingForegroundNotifier.OUTCOME_NOTIFICATION_ID
                    }
                ) {
                    delay(50)
                }
            }
            val outcomeNotifications = notificationManager.activeNotifications.filter {
                it.id == RecordingForegroundNotifier.OUTCOME_NOTIFICATION_ID
            }
            assertEquals(1, outcomeNotifications.size)
        } finally {
            activity.close()
            notificationManager.cancelAll()
        }
    }

    private fun grant(permission: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, permission)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
    }

    private fun enableSystemLocation() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd location set-location-enabled true")
            .close()
        val locationManager = requireNotNull(
            context.getSystemService(android.location.LocationManager::class.java),
        )
        assertEquals(true, locationManager.isLocationEnabled)
    }

    private fun operationId(prefix: String) =
        RecordingOperationId(prefix + ":" + UUID.randomUUID())
}
