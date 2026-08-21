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
import app.trailveil.data.location.LocationEngine
import app.trailveil.data.location.LocationUpdateRequest
import app.trailveil.data.location.RawLocationFix
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.data.recording.RecordingOperationId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // P4-046: this grant is what arms the abort in `NotificationStartContinuationTest`, which
        // needs the permission DENIED and cannot revoke it from inside instrumentation without
        // killing the run. It is not restored here for the same reason - a revoke from in here
        // would kill this run instead. The device is prepared from the host before a full suite:
        //   adb shell pm revoke app.trailveil android.permission.POST_NOTIFICATIONS
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
            // Which outcome, not just that one posted: both outcomes share the id, so without this
            // a service that told an interrupted user their walk was saved passed the whole suite.
            assertEquals(
                context.getString(app.trailveil.R.string.recording_completed_title),
                outcomeNotifications.single().notification.extras
                    .getString(android.app.Notification.EXTRA_TITLE),
            )
        } finally {
            activity.close()
            notificationManager.cancelAll()
        }
    }

    @Test
    fun anInjectedStreamFailureInterruptsAndPostsExactlyOneOutcomeNotification() = runBlocking {
        // Binds two halves that were inspection-only. Failure isolation: a location stream that
        // throws mid-recording must interrupt the exploration, not kill the process - the thrown
        // exception is injected through the same production seam the backpressure test uses, so
        // nothing here is simulated at the assertion layer. And the interruption half of the
        // outcome-notification criterion: exactly one notification on the outcome id, the same
        // shape the completion test binds for its half.
        enableSystemLocation()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        grant(Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()

        val application = context.applicationContext as TrailVeilApplication
        val container = application.appContainer
        val repository = container.recordingRepository
        container.reconcileRecordingStartup()
        container.setLocationEngineOverrideForTesting(ThrowingLocationEngine())
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            val sessionId = repository.beginStart(
                operationId("stream-failure-begin"),
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
            // The engine now emits its two fixes and throws. The service must interrupt.
            withTimeout(12_000) {
                while (repository.state().lifecycle != RecordingLifecycle.STOPPED) {
                    delay(50)
                }
            }
            val detail = withTimeout(5_000) {
                container.recordingHistory.sessionDetail(sessionId).first()
            }
            assertEquals(
                "INTERRUPT:location_stream_failure",
                requireNotNull(detail).session.stopReason,
            )
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
            // The interrupted text specifically - a swap to showCompleted() here told the user an
            // interrupted walk was saved, and no committed test failed.
            assertEquals(
                context.getString(app.trailveil.R.string.recording_interrupted_title),
                outcomeNotifications.single().notification.extras
                    .getString(android.app.Notification.EXTRA_TITLE),
            )
            // P4-048, and this is the WIRING half rather than the decision half. The decision -
            // that an announced exploration is ended rather than resumed - is owned by
            // `abandonedExplorationAction` and bound by JVM tests. What no JVM test can reach is
            // whether the real service actually records what it just announced, and this file is
            // the only place that drives the production service into the interrupt path at all.
            //
            // The failure this rules out is the one this decision table has met twice already, and
            // its own KDoc names: a guard correct in isolation while the wiring reaching it is bound
            // by nothing. Delete `announceInterruption`'s record and every JVM test still passes.
            assertTrue(
                "The service announced an interruption for session $sessionId without recording " +
                    "it, so reopening would resume the exploration the user was just told had " +
                    "ended (P4-048)",
                container.announcedInterruptionInThisRuntime(sessionId),
            )
            assertFalse(
                "The runtime claims an announcement for a session it never announced, so the " +
                    "record is a latch rather than a per-session fact (P4-048)",
                container.announcedInterruptionInThisRuntime(sessionId + 1_000L),
            )
        } finally {
            container.setLocationEngineOverrideForTesting(null)
            activity.close()
            notificationManager.cancelAll()
        }
    }

    /** Two honest fixes, then the stream dies the way a provider bug would kill it. */
    private class ThrowingLocationEngine : LocationEngine {
        override fun fixes(request: LocationUpdateRequest): Flow<RawLocationFix> = flow {
            repeat(2) { index ->
                emit(
                    RawLocationFix(
                        latitude = 25.0330 + index * 0.00001,
                        longitude = 121.5654,
                        horizontalAccuracyMeters = 5.0,
                        capturedAtElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos(),
                        epochMillis = System.currentTimeMillis(),
                    ),
                )
                delay(150)
            }
            throw IllegalStateException("injected location stream failure")
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
