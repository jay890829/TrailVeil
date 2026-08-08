package app.trailveil.recording

import app.trailveil.data.recording.RecordingOperationId
import app.trailveil.data.recording.RecordingRepositoryState
import app.trailveil.data.recording.RecoveryDisposition
import app.trailveil.data.recording.RecoveryResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RecordingRecoveryRetrierTest {
    @Test
    fun transientRecoveryFailureRetriesWithoutSelectingATechnicalTerminalPath() = runBlocking {
        var recoveries = 0
        var delays = 0
        val expected = RecoveryResult(
            operationId = RecordingOperationId("recovered-pending-stop"),
            disposition = RecoveryDisposition.PENDING_STOP_COMPLETED,
            state = RecordingRepositoryState(),
        )
        val actual = RecordingPersistenceRetrier(
            attempt = {
                recoveries += 1
                if (recoveries == 1) throw IOException("injected pre-commit failure")
                expected
            },
            retryDelay = { delays += 1 },
        ).runUntilResolved()

        assertEquals(expected, actual)
        assertEquals(2, recoveries)
        assertEquals(1, delays)
    }

    @Test
    fun cancellationStopsRecoveryWithoutRetrying() = runBlocking {
        var delays = 0
        try {
            RecordingPersistenceRetrier<Unit>(
                attempt = { throw CancellationException("service destroyed") },
                retryDelay = { delays += 1 },
            ).runUntilResolved()
            fail("expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, delays)
        }
    }
}
