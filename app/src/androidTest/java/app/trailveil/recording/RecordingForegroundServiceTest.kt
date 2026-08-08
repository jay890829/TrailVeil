package app.trailveil.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.RecordingStatus
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.location.LocationBackpressureException
import app.trailveil.data.location.LocationEngine
import app.trailveil.data.location.LocationFixOfferResult
import app.trailveil.data.location.LocationUpdateRequest
import app.trailveil.data.location.RawLocationFix
import app.trailveil.data.location.offerLocationFix
import app.trailveil.data.location.withLocationFixBuffer
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.data.recording.RecordingOperationId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun stickyRecoveryCompletesAPendingUserStopBeforeLocationCanResume() = runBlocking {
        enableSystemLocation()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val application = context.applicationContext as TrailVeilApplication
        val repository = application.appContainer.recordingRepository
        val sessionId = repository.beginStart(
            operationId("pending-stop-begin"),
            System.currentTimeMillis(),
            "instrumentation",
        ).sessionId
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            activity.onActivity {
                RecordingForegroundService.startFromVisibleActivity(it, sessionId)
            }
            withTimeout(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.ACTIVE) delay(50)
            }

            val requestedAt = System.currentTimeMillis()
            assertTrue(
                repository.requestStop(
                    operationId("pending-stop-request"),
                    sessionId,
                    requestedAt,
                    "user_notification_stop",
                ).requested,
            )
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
                while (repository.state().lifecycle != RecordingLifecycle.STOPPED) delay(50)
            }

            val database = TrailVeilDatabase.open(context)
            try {
                val session = requireNotNull(database.recordingDao().sessionById(sessionId))
                assertEquals(RecordingStatus.COMPLETED, session.status)
                assertEquals(requestedAt, session.endedAt)
                assertEquals("STOP:user_notification_stop", session.stopReason)
                assertTrue(
                    requireNotNull(database.recordingDao().sessionWithSegments(sessionId))
                        .segments
                        .none { it.startReason == "PROCESS_RECOVERY" },
                )
            } finally {
                database.close()
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
    fun locationBackpressureInterruptsAndClosesTheOpenSegment() = runBlocking {
        enableSystemLocation()
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val application = context.applicationContext as TrailVeilApplication
        val container = application.appContainer
        val repository = container.recordingRepository
        // Match the production entry gate: app-startup reconciliation must finish before a new
        // STARTING reservation can be created, otherwise the reconciler may honestly claim it.
        container.reconcileRecordingStartup()
        val engine = StoragePressureLocationEngine(totalFixes = 100_000)
        container.setLocationEngineOverrideForTesting(engine)
        val sessionId = repository.beginStart(
            operationId("backpressure-begin"),
            System.currentTimeMillis(),
            "instrumentation",
        ).sessionId
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val releaseRoomWriter = CompletableDeferred<Unit>()
        val roomWriterLocked = CompletableDeferred<Unit>()
        val blockingDatabase = TrailVeilDatabase.open(context)
        var blockingTransaction: Job? = null
        try {
            activity.onActivity {
                RecordingForegroundService.startFromVisibleActivity(it, sessionId)
            }
            assertTrue("the injected session never became ACTIVE", withTimeoutOrNull(10_000) {
                while (repository.state().lifecycle != RecordingLifecycle.ACTIVE) delay(50)
                true
            } == true)

            blockingTransaction = async(Dispatchers.IO) {
                blockingDatabase.withTransaction {
                    blockingDatabase.openHelper.writableDatabase.execSQL(
                        "UPDATE recording_sessions " +
                            "SET created_app_version = created_app_version WHERE id = ?",
                        arrayOf(sessionId),
                    )
                    roomWriterLocked.complete(Unit)
                    releaseRoomWriter.await()
                }
            }
            roomWriterLocked.await()
            engine.startProducing.complete(Unit)
            assertTrue("the 100k callback producer did not finish", withTimeoutOrNull(30_000) {
                engine.productionFinished.await()
                true
            } == true)
            assertTrue(engine.failed > 0)

            releaseRoomWriter.complete(Unit)
            blockingTransaction.join()
            // The emulator must drain every fix that was accepted into the bounded queue through
            // individual durable Room transactions before surfacing the terminal close cause.
            assertTrue("the drained overflow did not terminalize the session", withTimeoutOrNull(30_000) {
                while (repository.state().lifecycle != RecordingLifecycle.STOPPED) delay(50)
                true
            } == true)

            val database = TrailVeilDatabase.open(context)
            try {
                val session = requireNotNull(database.recordingDao().sessionById(sessionId))
                assertEquals(RecordingStatus.INTERRUPTED, session.status)
                assertEquals("INTERRUPT:location_backpressure", session.stopReason)
                assertTrue(session.acceptedPointCount > 0L)
                assertTrue(session.rejectedPointCount > 0L)
                assertEquals(engine.delivered.toLong(), session.acceptedPointCount + session.rejectedPointCount)
                assertEquals(0, engine.coalesced)
                assertEquals(
                    engine.totalFixes.toLong(),
                    session.acceptedPointCount + session.rejectedPointCount +
                        engine.coalesced.toLong() + engine.failed.toLong(),
                )
                val segment = requireNotNull(
                    database.recordingDao().sessionWithSegments(sessionId),
                ).segments.single()
                assertNotNull(segment.endedAt)
                assertEquals("INTERRUPT:location_backpressure", segment.endReason)
                assertNull(segment.openSlot)
            } finally {
                database.close()
            }
        } finally {
            releaseRoomWriter.complete(Unit)
            blockingTransaction?.cancelAndJoin()
            blockingDatabase.close()
            runCatching {
                if (repository.state().lifecycle == RecordingLifecycle.ACTIVE) {
                    repository.interrupt(
                        operationId("backpressure-test-cleanup"),
                        sessionId,
                        System.currentTimeMillis(),
                        "test_cleanup",
                    )
                }
            }
            container.setLocationEngineOverrideForTesting(null)
            context.stopService(Intent(context, RecordingForegroundService::class.java))
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
                    action = "app.trailveil.action.UNKNOWN_TEST"
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

    private class StoragePressureLocationEngine(
        val totalFixes: Int,
    ) : LocationEngine {
        val startProducing = CompletableDeferred<Unit>()
        val productionFinished = CompletableDeferred<Unit>()
        var delivered = 0
            private set
        var failed = 0
            private set
        val coalesced = 0

        override fun fixes(request: LocationUpdateRequest): Flow<RawLocationFix> = callbackFlow {
            startProducing.await()
            val elapsedBase = SystemClock.elapsedRealtimeNanos()
            val epochBase = System.currentTimeMillis()
            repeat(totalFixes) { index ->
                val result = offerLocationFix(
                    RawLocationFix(
                        latitude = if (index % 2 == 0) 25.0 else 91.0,
                        longitude = 121.0,
                        horizontalAccuracyMeters = 5.0,
                        capturedAtElapsedRealtimeNanos = elapsedBase + index,
                        epochMillis = epochBase,
                    ),
                )
                when (result) {
                    LocationFixOfferResult.DELIVERED -> delivered += 1
                    LocationFixOfferResult.ALREADY_CLOSED,
                    LocationFixOfferResult.OVERFLOW_TERMINATED -> failed += 1
                }
            }
            productionFinished.complete(Unit)
            awaitClose()
        }.withLocationFixBuffer()
    }
}
