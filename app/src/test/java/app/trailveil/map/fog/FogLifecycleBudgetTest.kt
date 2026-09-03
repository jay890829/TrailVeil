package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FogLifecycleBudgetTest {
    @Test
    fun pausePreservesActiveTimeAndOwnerAcrossResume() {
        var nowNanos = 0L
        val budget = FogLifecycleBudget(totalMillis = 100L) { nowNanos }

        val first = requireNotNull(budget.start(owner = 7L, cameraEpoch = 11L))
        nowNanos += 35L * NANOS_PER_MILLISECOND
        assertEquals(65L, budget.remainingMillis())

        val paused = requireNotNull(budget.pause(first))
        assertEquals(65L, paused.remainingMillis)
        assertEquals(7L, paused.owner)
        assertEquals(11L, paused.cameraEpoch)
        assertFalse(budget.isCurrent(first))

        nowNanos += 500L * NANOS_PER_MILLISECOND
        val resumed = requireNotNull(budget.resume(paused))
        assertEquals(7L, resumed.owner)
        assertEquals(11L, resumed.cameraEpoch)
        assertTrue(resumed.token > first.token)
        assertEquals(65L, resumed.remainingMillis)
        assertTrue(budget.isCurrent(resumed))
    }

    @Test
    fun staleRunCannotCompleteAfterPauseAndResume() {
        var nowNanos = 10L * NANOS_PER_MILLISECOND
        val budget = FogLifecycleBudget(totalMillis = 20L) { nowNanos }
        val first = requireNotNull(budget.start(owner = 3L, cameraEpoch = 4L))

        nowNanos += 4L * NANOS_PER_MILLISECOND
        val paused = requireNotNull(budget.pause(first))
        val resumed = requireNotNull(budget.resume(paused))

        assertFalse(budget.complete(first))
        assertTrue(budget.complete(resumed))
        assertFalse(budget.complete(resumed))
    }

    @Test
    fun expiredPreservedBudgetCannotBeReplacedWithUnlimitedTime() {
        var nowNanos = 0L
        val budget = FogLifecycleBudget(totalMillis = 20L) { nowNanos }
        val first = requireNotNull(budget.start(owner = 1L, cameraEpoch = 2L))

        nowNanos += 20L * NANOS_PER_MILLISECOND
        val paused = requireNotNull(budget.pause(first))
        assertEquals(0L, paused.remainingMillis)
        assertNull(budget.resume(paused))
        assertEquals(0L, budget.remainingMillis())
    }

    @Test
    fun cancellationClosesBudgetWithoutAStaleCompletion() {
        val budget = FogLifecycleBudget(totalMillis = 100L)
        val lease = requireNotNull(budget.start(owner = 9L, cameraEpoch = 10L))

        assertTrue(budget.cancel(lease))
        assertFalse(budget.isCurrent(lease))
        assertFalse(budget.complete(lease))
        assertNull(budget.start(owner = 9L, cameraEpoch = 10L))
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
