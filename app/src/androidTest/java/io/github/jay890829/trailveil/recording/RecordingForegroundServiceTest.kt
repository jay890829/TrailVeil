package io.github.jay890829.trailveil.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jay890829.trailveil.MainActivity
import io.github.jay890829.trailveil.TrailVeilApplication
import io.github.jay890829.trailveil.data.db.RecordingStatus
import io.github.jay890829.trailveil.data.db.TrailVeilDatabase
import io.github.jay890829.trailveil.data.recording.RecordingLifecycle
import io.github.jay890829.trailveil.data.recording.RecordingOperationId
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingForegroundServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun visibleActivityStartBecomesActiveWithoutNotificationGrantAndNotificationStopCompletes() =
        runBlocking {
            enableSystemLocation()
            grant(Manifest.permission.ACCESS_COARSE_LOCATION)
            grant(Manifest.permission.ACCESS_FINE_LOCATION)
            assertEquals(
                PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
            )

            val application = context.applicationContext as TrailVeilApplication
            val repository = application.appContainer.recordingRepository
            val sessionId = repository.beginStart(
                operationId("begin"),
                System.currentTimeMillis(),
                "instrumentation",
            ).sessionId

            val activity = ActivityScenario.launch(MainActivity::class.java)
            try {
                activity.onActivity {
                    RecordingForegroundService.startFromVisibleActivity(it, sessionId)
                }
                withTimeout(10_000) {
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
                withTimeout(10_000) {
                    while (repository.state().lifecycle != RecordingLifecycle.STOPPED) {
                        delay(50)
                    }
                }

                val database = TrailVeilDatabase.open(context)
                try {
                    assertEquals(
                        RecordingStatus.COMPLETED,
                        database.recordingDao().sessionById(sessionId)?.status,
                    )
                } finally {
                    database.close()
                }
            } finally {
                activity.close()
            }
        }

    @Test
    fun stickyRecoveryRotatesToANewProcessRecoverySegmentBeforeCollecting() = runBlocking {
        enableSystemLocation()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val application = context.applicationContext as TrailVeilApplication
        val repository = application.appContainer.recordingRepository
        val sessionId = repository.beginStart(
            operationId("recovery-begin"),
            System.currentTimeMillis(),
            "instrumentation",
        ).sessionId

        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            activity.onActivity {
                RecordingForegroundService.startFromVisibleActivity(it, sessionId)
            }
            withTimeout(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.ACTIVE) {
                    delay(50)
                }
            }

            assertTrue(
                context.stopService(
                    Intent(context, RecordingForegroundService::class.java),
                ),
            )
            delay(250)
            assertEquals(RecordingLifecycle.ACTIVE, repository.state().lifecycle)

            activity.onActivity {
                ContextCompat.startForegroundService(
                    it,
                    Intent(it, RecordingForegroundService::class.java),
                )
            }
            withTimeout(10_000) {
                while (true) {
                    val database = TrailVeilDatabase.open(context)
                    val recovered = try {
                        database.recordingDao().sessionWithSegments(sessionId)
                            ?.segments
                            ?.any { it.startReason == "PROCESS_RECOVERY" } == true
                    } finally {
                        database.close()
                    }
                    if (recovered) break
                    delay(50)
                }
            }

            context.startService(
                Intent(context, RecordingForegroundService::class.java).apply {
                    action = RecordingForegroundService.ACTION_STOP
                    putExtra(RecordingForegroundService.EXTRA_SESSION_ID, sessionId)
                },
            )
            withTimeout(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.STOPPED) {
                    delay(50)
                }
            }
        } finally {
            activity.close()
        }
    }
    @Test
    fun disablingSystemLocationInterruptsInsteadOfLeavingAnActiveSession() = runBlocking {
        enableSystemLocation()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val application = context.applicationContext as TrailVeilApplication
        val repository = application.appContainer.recordingRepository
        val sessionId = repository.beginStart(
            operationId("provider-disable-begin"),
            System.currentTimeMillis(),
            "instrumentation",
        ).sessionId
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            activity.onActivity {
                RecordingForegroundService.startFromVisibleActivity(it, sessionId)
            }
            withTimeout(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.ACTIVE) {
                    delay(50)
                }
            }

            setSystemLocation(enabled = false)
            withTimeout(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.STOPPED) {
                    delay(50)
                }
            }
            val database = TrailVeilDatabase.open(context)
            try {
                val session = requireNotNull(database.recordingDao().sessionById(sessionId))
                assertEquals(RecordingStatus.INTERRUPTED, session.status)
                assertEquals("INTERRUPT:location_disabled", session.stopReason)
            } finally {
                database.close()
            }
        } finally {
            setSystemLocation(enabled = true)
            activity.close()
        }
    }
    @Test
    fun staleNotificationStopCannotStopTheReplacementRuntime() = runBlocking {
        enableSystemLocation()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val application = context.applicationContext as TrailVeilApplication
        val repository = application.appContainer.recordingRepository
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            val firstSessionId = repository.beginStart(
                operationId("stale-first-begin"),
                System.currentTimeMillis(),
                "instrumentation",
            ).sessionId
            activity.onActivity {
                RecordingForegroundService.startFromVisibleActivity(it, firstSessionId)
            }
            withTimeout(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.ACTIVE) delay(50)
            }
            val staleStop = RecordingForegroundNotifier(context)
                .notification(firstSessionId)
                .actions
                .single()
                .actionIntent
            staleStop.send()
            withTimeout(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.STOPPED) delay(50)
            }

            val replacementSessionId = repository.beginStart(
                operationId("stale-replacement-begin"),
                System.currentTimeMillis(),
                "instrumentation",
            ).sessionId
            activity.onActivity {
                RecordingForegroundService.startFromVisibleActivity(it, replacementSessionId)
            }
            withTimeout(10_000) {
                while (
                    repository.state().sessionId != replacementSessionId ||
                    repository.state().lifecycle != RecordingLifecycle.ACTIVE
                ) {
                    delay(50)
                }
            }

            context.startService(
                Intent(context, RecordingForegroundService::class.java).apply {
                    action = "io.github.jay890829.trailveil.action.UNKNOWN_TEST"
                },
            )
            delay(500)
            assertEquals(replacementSessionId, repository.state().sessionId)
            assertEquals(RecordingLifecycle.ACTIVE, repository.state().lifecycle)

            staleStop.send()
            delay(500)
            assertEquals(replacementSessionId, repository.state().sessionId)
            assertEquals(RecordingLifecycle.ACTIVE, repository.state().lifecycle)

            setSystemLocation(enabled = false)
            withTimeout(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.STOPPED) delay(50)
            }
            val database = TrailVeilDatabase.open(context)
            try {
                assertEquals(
                    RecordingStatus.INTERRUPTED,
                    database.recordingDao().sessionById(replacementSessionId)?.status,
                )
            } finally {
                database.close()
            }
        } finally {
            setSystemLocation(enabled = true)
            activity.close()
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
        setSystemLocation(enabled = true)
        val locationManager = requireNotNull(
            context.getSystemService(android.location.LocationManager::class.java),
        )
        assertTrue(locationManager.isLocationEnabled)
    }

    private fun setSystemLocation(enabled: Boolean) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd location set-location-enabled $enabled")
            .close()
    }

    private fun operationId(prefix: String) =
        RecordingOperationId("$prefix:${UUID.randomUUID()}")
}
