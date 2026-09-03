package app.trailveil.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canonical fog pass driver, tested where the defect it fixes is actually visible.
 *
 * The failure this guards against only ever reproduced on a host slow enough that one render,
 * style install and retirement takes longer than the gap between merged change-feed pages, so a
 * device gate on a fast machine is no gate at all. These cases hold the property directly: a
 * content revision arriving while a pass is in flight must never cancel it, and must still buy
 * exactly one follow-up.
 *
 * A [MutableStateFlow] stands in for `snapshotFlow` faithfully for this purpose: both replay their
 * current value to a new collector, and both conflate.
 */
class FogCanonicalPassCoalescingTest {

    @Test
    fun revisionsDuringAPassNeverCancelItAndBuyExactlyOneFollowUp() = runTest {
        val revisions = MutableStateFlow(0L)
        var entries = 0
        var firstPassWasCancelled = false
        val held = CompletableDeferred<Unit>()
        val driver = launch {
            driveCanonicalFogPasses(revisions, { revisions.value }) {
                entries += 1
                if (entries == 1) {
                    try {
                        held.await()
                    } catch (cancelled: CancellationException) {
                        firstPassWasCancelled = true
                        throw cancelled
                    }
                }
                CanonicalFogPassOutcome.AWAIT_NEXT_REVISION
            }
        }
        advanceUntilIdle()
        assertEquals("the first pass must be entered once", 1, entries)

        repeat(5) { revisions.value += 1L }
        advanceUntilIdle()
        assertEquals("a revision must not start a pass while one is running", 1, entries)
        assertFalse("a revision must never cancel a pass in flight", firstPassWasCancelled)

        held.complete(Unit)
        advanceUntilIdle()
        assertEquals("five revisions during one pass coalesce into one follow-up", 2, entries)
        driver.cancel()
    }

    @Test
    fun aRevisionRaisedInsideThePassStillBuysAFollowUp() = runTest {
        val revisions = MutableStateFlow(0L)
        var entries = 0
        val driver = launch {
            driveCanonicalFogPasses(revisions, { revisions.value }) {
                entries += 1
                if (entries == 1) revisions.value += 1L
                CanonicalFogPassOutcome.AWAIT_NEXT_REVISION
            }
        }
        advanceUntilIdle()
        assertEquals(
            "a merge landing while the pass composes is not in the mosaic it installed, so it " +
                "must still wake the driver - this pins reading the counter BEFORE the pass",
            2,
            entries,
        )
        driver.cancel()
    }

    @Test
    fun anIdleDriverRunsOnePassPerRevision() = runTest {
        val revisions = MutableStateFlow(0L)
        var entries = 0
        val driver = launch {
            driveCanonicalFogPasses(revisions, { revisions.value }) {
                entries += 1
                CanonicalFogPassOutcome.AWAIT_NEXT_REVISION
            }
        }
        advanceUntilIdle()
        assertEquals(1, entries)
        repeat(3) {
            revisions.value += 1L
            advanceUntilIdle()
        }
        assertEquals(4, entries)
        driver.cancel()
    }

    @Test
    fun aPassReportingStopEndsTheDriver() = runTest {
        val revisions = MutableStateFlow(0L)
        var entries = 0
        val driver = launch {
            driveCanonicalFogPasses(revisions, { revisions.value }) {
                entries += 1
                CanonicalFogPassOutcome.STOP
            }
        }
        advanceUntilIdle()
        assertTrue("the driver must complete after STOP", driver.isCompleted)
        repeat(10) { revisions.value += 1L }
        advanceUntilIdle()
        assertEquals("STOP ends the loop for good", 1, entries)
    }
}
