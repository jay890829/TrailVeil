package app.trailveil.feature.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbandonedActionRunnerTest {
    @Test
    fun `restart interruption finishes after its composition is cancelled`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val releaseInterrupt = CompletableDeferred<Unit>()
        var interruptCompleted = false
        val released = mutableListOf<Long>()

        val job = launch {
            runClaimedAbandonedAction(
                action = AbandonedExplorationAction.Interrupt(7L, stoppedRecordingAt = 1_000L),
                resume = { error("resume was not selected") },
                interrupt = {
                    entered.complete(Unit)
                    releaseInterrupt.await()
                    interruptCompleted = true
                    true
                },
                release = { released += it; true },
            )
        }
        entered.await()
        job.cancel()
        releaseInterrupt.complete(Unit)
        job.join()

        assertTrue("the durable interrupt was cancelled with its composition", interruptCompleted)
        assertEquals(emptyList<Long>(), released)
    }

    @Test
    fun `failed restart interruption returns the claim for a later screen`() = runTest {
        val released = mutableListOf<Long>()

        runClaimedAbandonedAction(
            action = AbandonedExplorationAction.Interrupt(7L, stoppedRecordingAt = 1_000L),
            resume = { error("resume was not selected") },
            interrupt = { false },
            release = { released += it; true },
        )

        assertEquals(listOf(7L), released)
    }

    @Test
    fun `cancelled resume returns the claim because no outcome was produced`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val released = mutableListOf<Long>()
        val job = launch {
            runClaimedAbandonedAction(
                action = AbandonedExplorationAction.Resume(7L),
                resume = {
                    entered.complete(Unit)
                    awaitCancellation()
                },
                interrupt = { error("interrupt was not selected") },
                release = { released += it; true },
            )
        }
        entered.await()
        job.cancelAndJoin()

        assertEquals(listOf(7L), released)
    }

    @Test
    fun `a completed blocked resume remains one attempt`() = runTest {
        var resumed = false
        var released = false

        runClaimedAbandonedAction(
            action = AbandonedExplorationAction.Resume(7L),
            resume = { resumed = true },
            interrupt = { error("interrupt was not selected") },
            release = { released = true; true },
        )

        assertTrue(resumed)
        assertFalse(released)
    }
}
