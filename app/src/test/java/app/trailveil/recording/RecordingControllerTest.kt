package app.trailveil.recording

import app.trailveil.data.recording.BeginStartResult
import app.trailveil.data.recording.RecordingLifecycle
import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.data.recording.RecordingRepositoryState
import app.trailveil.data.recording.StartDisposition
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingControllerTest {
    @Test
    fun `preflight reports one actionable blocker without launching`() = runBlocking {
        val commands = FakeCommands()
        val launcher = FakeLauncher()
        val controller = controller(
            preflight = RecordingStartPreflight {
                RecordingStartBlocker.MISSING_FINE_LOCATION
            },
            commands = commands,
            launcher = launcher,
        )

        assertEquals(
            RecordingStartOutcome.Blocked(RecordingStartBlocker.MISSING_FINE_LOCATION),
            controller.startFromVisibleActivity(activityVisible = true),
        )
        assertEquals(0, commands.beginCalls)
        assertEquals(emptyList<Long>(), launcher.sessions)
    }

    @Test
    fun `resuming an abandoned exploration reserves nothing`() = runBlocking {
        // `prepareStart` inserts a fresh session whenever it finds nothing active or reserved, so a
        // resume that went through a reservation would record an exploration the user never started
        // if the abandoned row terminalized between the screen reading it and the transaction.
        val commands = FakeCommands()
        val launcher = FakeLauncher()

        val outcome = controller(commands = commands, launcher = launcher)
            .resumeAbandonedFromVisibleActivity(activityVisible = true, sessionId = 12L)

        assertEquals(RecordingResumeOutcome.ServiceRequested(12L), outcome)
        assertEquals(0, commands.beginCalls)
        assertEquals(0, commands.failCalls)
        // The service is asked about the row that already exists, not about a new one.
        assertEquals(listOf(12L), launcher.sessions)
    }

    @Test
    fun `ending an exploration the device restarted under starts no service`() = runBlocking {
        val commands = FakeCommands()
        val launcher = FakeLauncher()

        val ended = controller(commands = commands, launcher = launcher)
            .interruptAbandonedAcrossRestart(
                sessionId = 12L,
                stoppedRecordingAtEpochMillis = LAST_POINT_AT,
            )

        assertTrue(ended)
        assertEquals(listOf(Triple(12L, LAST_POINT_AT, "device_restarted")), commands.interruptCalls)
        // The whole point of this path is that nothing starts collecting: the row is being closed.
        assertEquals(emptyList<Long>(), launcher.sessions)
        assertEquals(0, commands.beginCalls)
    }

    @Test
    fun `ending is not gated on permission or a visible activity`() = runBlocking {
        // A user who revoked location, or switched it off, must still get the row closed - refusing
        // here would leave open exactly the session PLAN.md requires be marked interrupted.
        val commands = FakeCommands()
        val controller = controller(
            preflight = RecordingStartPreflight { RecordingStartBlocker.MISSING_LOCATION_PERMISSION },
            commands = commands,
            launcher = FakeLauncher(),
        )

        assertTrue(
            controller.interruptAbandonedAcrossRestart(
                sessionId = 12L,
                stoppedRecordingAtEpochMillis = LAST_POINT_AT,
            ),
        )
        assertEquals(listOf(Triple(12L, LAST_POINT_AT, "device_restarted")), commands.interruptCalls)
    }

    @Test
    fun `a failed ending is reported rather than assumed`() = runBlocking {
        val commands = FakeCommands(interruptFailure = IOException("disk"))

        assertFalse(
            controller(commands = commands, launcher = FakeLauncher())
                .interruptAbandonedAcrossRestart(
                    sessionId = 12L,
                    stoppedRecordingAtEpochMillis = LAST_POINT_AT,
                ),
        )
    }

    @Test
    fun `an exploration that recorded nothing is dated from its own start, not from now`() = runBlocking {
        // The caller passes null only when the session never accepted a point. Falling back to now
        // is safe because the store clamps the terminal instant up to the session start anyway, but
        // the caller is expected to supply the start itself so the ending is not dated from whenever
        // the user happened to reopen the app.
        val commands = FakeCommands()

        controller(commands = commands, launcher = FakeLauncher(), clock = { FIXED_NOW })
            .interruptAbandonedAcrossRestart(sessionId = 12L, stoppedRecordingAtEpochMillis = null)

        assertEquals(listOf(Triple(12L, FIXED_NOW, "device_restarted")), commands.interruptCalls)
    }

    @Test
    fun `the ending is dated from the last recorded point, however late it is discovered`() = runBlocking {
        // A row abandoned by a reboot is found whenever the user next opens the app. Dating it from
        // that moment publishes an exploration whose duration is mostly the hours the phone was off.
        val commands = FakeCommands()

        controller(commands = commands, launcher = FakeLauncher(), clock = { FIXED_NOW })
            .interruptAbandonedAcrossRestart(
                sessionId = 12L,
                stoppedRecordingAtEpochMillis = LAST_POINT_AT,
            )

        assertEquals(listOf(Triple(12L, LAST_POINT_AT, "device_restarted")), commands.interruptCalls)
        assertTrue("the fixture must model a late discovery", FIXED_NOW - LAST_POINT_AT > 8 * 3_600_000L)
    }

    @Test
    fun `a blocked resume launches nothing and names the blocker`() = runBlocking {
        val commands = FakeCommands()
        val launcher = FakeLauncher()
        val controller = controller(
            preflight = RecordingStartPreflight { RecordingStartBlocker.LOCATION_DISABLED },
            commands = commands,
            launcher = launcher,
        )

        assertEquals(
            RecordingResumeOutcome.Blocked(RecordingStartBlocker.LOCATION_DISABLED),
            controller.resumeAbandonedFromVisibleActivity(activityVisible = true, sessionId = 12L),
        )
        assertEquals(0, commands.beginCalls)
        assertEquals(emptyList<Long>(), launcher.sessions)
    }

    @Test
    fun `a refused foreground start is reported without inventing persistence`() = runBlocking {
        val commands = FakeCommands()
        val launcher = FakeLauncher(failure = IllegalStateException("refused"))

        val outcome = controller(commands = commands, launcher = launcher)
            .resumeAbandonedFromVisibleActivity(activityVisible = true, sessionId = 12L)

        assertTrue(outcome is RecordingResumeOutcome.LaunchFailure)
        // Nothing durable was attempted, so nothing durable may be recorded as failed either.
        assertEquals(0, commands.failCalls)
    }

    @Test
    fun `prepared reservation is persisted before service launch`() = runBlocking {
        val events = mutableListOf<String>()
        val commands = FakeCommands(events = events)
        val launcher = FakeLauncher(events = events)

        val outcome = controller(commands = commands, launcher = launcher)
            .startFromVisibleActivity(activityVisible = true)

        assertEquals(
            RecordingStartOutcome.ServiceRequested(7L, StartDisposition.PREPARED),
            outcome,
        )
        assertEquals(listOf("begin", "launch:7"), events)
    }

    @Test
    fun `a recreation retry reuses the caller owned begin operation id`() = runBlocking {
        val commands = FakeCommands()
        val generatedPurposes = mutableListOf<String>()
        val stableId = RecordingOperationId("begin-start-notification:stable")
        val controller = controller(
            commands = commands,
            operationIds = RecordingControllerOperationIds { purpose ->
                generatedPurposes += purpose
                RecordingOperationId("$purpose:generated")
            },
        )

        controller.startFromVisibleActivity(
            activityVisible = true,
            beginOperationId = stableId,
        )
        controller.startFromVisibleActivity(
            activityVisible = true,
            beginOperationId = stableId,
        )

        assertEquals(listOf(stableId, stableId), commands.beginOperationIds)
        assertEquals(emptyList<String>(), generatedPurposes)
    }

    @Test
    fun `launch rejection durably fails a starting reservation`() = runBlocking {
        val commands = FakeCommands()
        val launcher = FakeLauncher(failure = SecurityException("denied"))

        val outcome = controller(commands = commands, launcher = launcher)
            .startFromVisibleActivity(activityVisible = true)

        assertEquals(
            RecordingStartOutcome.LaunchFailure(
                sessionId = 7L,
                kind = RecordingStartFailureKind.SECURITY,
                startFailurePersisted = true,
            ),
            outcome,
        )
        assertEquals(1, commands.failCalls)
    }

    @Test
    fun `launch failure remains honest when fail-start persistence also fails`() = runBlocking {
        val commands = FakeCommands(failFailure = IOException("storage unavailable"))
        val launcher = FakeLauncher(failure = IllegalStateException("runtime"))

        val outcome = controller(commands = commands, launcher = launcher)
            .startFromVisibleActivity(activityVisible = true)

        assertEquals(
            RecordingStartOutcome.LaunchFailure(
                sessionId = 7L,
                kind = RecordingStartFailureKind.RUNTIME,
                startFailurePersisted = false,
            ),
            outcome,
        )
    }

    @Test
    fun `structured cancellation is never converted into a command outcome`() = runBlocking {
        try {
            controller(commands = FakeCommands(beginFailure = CancellationException("cancel begin")))
                .startFromVisibleActivity(activityVisible = true)
            throw AssertionError("expected begin cancellation")
        } catch (_: CancellationException) { }

        try {
            controller(
                commands = FakeCommands(failFailure = CancellationException("cancel fail")),
                launcher = FakeLauncher(failure = SecurityException("denied")),
            ).startFromVisibleActivity(activityVisible = true)
            throw AssertionError("expected fail-start cancellation")
        } catch (_: CancellationException) { }
    }
    @Test
    fun `existing active reservation is never relabeled failed-to-start`() = runBlocking {
        val commands = FakeCommands(disposition = StartDisposition.ALREADY_ACTIVE)
        val launcher = FakeLauncher(failure = SecurityException("denied"))

        val outcome = controller(commands = commands, launcher = launcher)
            .startFromVisibleActivity(activityVisible = true)

        assertTrue(outcome is RecordingStartOutcome.LaunchFailure)
        assertFalse((outcome as RecordingStartOutcome.LaunchFailure).startFailurePersisted)
        assertEquals(0, commands.failCalls)
    }

    @Test
    fun `preflight requires visible activity fine location and enabled provider`() {
        assertEquals(
            RecordingStartBlocker.ACTIVITY_NOT_VISIBLE,
            blocker(visible = false, coarse = true, fine = true, enabled = true),
        )
        assertEquals(
            RecordingStartBlocker.MISSING_LOCATION_PERMISSION,
            blocker(visible = true, coarse = false, fine = false, enabled = true),
        )
        assertEquals(
            RecordingStartBlocker.MISSING_FINE_LOCATION,
            blocker(visible = true, coarse = true, fine = false, enabled = true),
        )
        assertEquals(
            RecordingStartBlocker.LOCATION_DISABLED,
            blocker(visible = true, coarse = true, fine = true, enabled = false),
        )
        assertEquals(
            null,
            blocker(visible = true, coarse = true, fine = true, enabled = true),
        )
    }

    private fun blocker(
        visible: Boolean,
        coarse: Boolean,
        fine: Boolean,
        enabled: Boolean,
    ) = evaluateRecordingStartPreflight(
        RecordingStartPreflightSnapshot(visible, coarse, fine, enabled),
    )

    private fun controller(
        preflight: RecordingStartPreflight = RecordingStartPreflight { null },
        commands: FakeCommands = FakeCommands(),
        launcher: FakeLauncher = FakeLauncher(),
        operationIds: RecordingControllerOperationIds = RecordingControllerOperationIds { purpose ->
            RecordingOperationId("$purpose:test")
        },
        clock: RecordingControllerClock = RecordingControllerClock { 123L },
    ) = RecordingController(
        preflight = preflight,
        commands = commands,
        launcher = launcher,
        clock = clock,
        operationIds = operationIds,
        createdAppVersion = "test",
    )

    private companion object {
        /** A walk that stopped recording in the morning. */
        const val LAST_POINT_AT = 1_700_000_000_000L

        /** Discovered when the user reopened the app that evening, nine hours later. */
        const val FIXED_NOW = LAST_POINT_AT + 9 * 3_600_000L
    }
}

private class FakeCommands(
    private val disposition: StartDisposition = StartDisposition.PREPARED,
    private val beginFailure: Exception? = null,
    private val failFailure: Exception? = null,
    private val interruptFailure: Exception? = null,
    private val events: MutableList<String> = mutableListOf(),
) : RecordingStartCommands {
    var beginCalls = 0
    var failCalls = 0
    val beginOperationIds = mutableListOf<RecordingOperationId>()
    val interruptCalls = mutableListOf<Triple<Long, Long, String>>()

    override suspend fun beginStart(
        operationId: RecordingOperationId,
        startedAtEpochMillis: Long,
        createdAppVersion: String,
    ): BeginStartResult {
        beginCalls++
        beginOperationIds += operationId
        events += "begin"
        beginFailure?.let { throw it }
        return BeginStartResult(
            operationId = operationId,
            sessionId = 7L,
            disposition = disposition,
            state = RecordingRepositoryState(
                sessionId = 7L,
                lifecycle = when (disposition) {
                    StartDisposition.PREPARED,
                    StartDisposition.ALREADY_STARTING,
                    -> RecordingLifecycle.STARTING
                    StartDisposition.ALREADY_ACTIVE -> RecordingLifecycle.ACTIVE
                },
            ),
        )
    }

    override suspend fun failStart(
        operationId: RecordingOperationId,
        sessionId: Long,
        failedAtEpochMillis: Long,
        message: String,
    ) {
        failCalls++
        failFailure?.let { throw it }
    }

    override suspend fun interrupt(
        operationId: RecordingOperationId,
        sessionId: Long,
        interruptedAtEpochMillis: Long,
        reason: String,
    ) {
        interruptCalls += Triple(sessionId, interruptedAtEpochMillis, reason)
        events += "interrupt:$sessionId"
        interruptFailure?.let { throw it }
    }
}

private class FakeLauncher(
    private val failure: RuntimeException? = null,
    private val events: MutableList<String> = mutableListOf(),
) : RecordingServiceLauncher {
    val sessions = mutableListOf<Long>()

    override fun start(sessionId: Long) {
        sessions += sessionId
        events += "launch:$sessionId"
        failure?.let { throw it }
    }
}
