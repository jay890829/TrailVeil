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
    ) = RecordingController(
        preflight = preflight,
        commands = commands,
        launcher = launcher,
        clock = RecordingControllerClock { 123L },
        operationIds = RecordingControllerOperationIds { purpose ->
            RecordingOperationId("$purpose:test")
        },
        createdAppVersion = "test",
    )
}

private class FakeCommands(
    private val disposition: StartDisposition = StartDisposition.PREPARED,
    private val beginFailure: Exception? = null,
    private val failFailure: Exception? = null,
    private val events: MutableList<String> = mutableListOf(),
) : RecordingStartCommands {
    var beginCalls = 0
    var failCalls = 0

    override suspend fun beginStart(
        operationId: RecordingOperationId,
        startedAtEpochMillis: Long,
        createdAppVersion: String,
    ): BeginStartResult {
        beginCalls++
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
