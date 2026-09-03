package app.trailveil.map.fog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FogSnapshotProofBudgetTest {
    @Test
    fun lifecycleAndCameraStaleCallbacksDoNotConsumeAttempts() {
        val budget = FogSnapshotProofBudget(maxAttempts = 2)
        val first = requireNotNull(budget.begin(lifecycleEpoch = 1L, cameraEpoch = 5L))

        assertFalse(budget.isCurrent(first, lifecycleEpoch = 2L, cameraEpoch = 5L))
        assertTrue(budget.abandon(first))
        val afterLifecycle = requireNotNull(budget.begin(lifecycleEpoch = 2L, cameraEpoch = 5L))
        assertTrue(afterLifecycle.number == 1)

        assertFalse(budget.isCurrent(afterLifecycle, lifecycleEpoch = 2L, cameraEpoch = 6L))
        assertTrue(budget.abandon(afterLifecycle))
        val afterCamera = requireNotNull(budget.begin(lifecycleEpoch = 2L, cameraEpoch = 6L))
        assertTrue(afterCamera.number == 1)
    }

    @Test
    fun genuineFailureAdvancesExactlyOnceAndExhaustionIsTerminal() {
        val budget = FogSnapshotProofBudget(maxAttempts = 2)
        val first = requireNotNull(budget.begin(lifecycleEpoch = 0L, cameraEpoch = 0L))

        assertTrue(budget.recordFailure(first) == true)
        val second = requireNotNull(budget.begin(lifecycleEpoch = 0L, cameraEpoch = 0L))
        assertTrue(second.number == 2)
        assertTrue(budget.recordFailure(second) == false)
        assertNull(budget.begin(lifecycleEpoch = 0L, cameraEpoch = 0L))
        assertFalse(budget.recordFailure(second) == true)
    }

    @Test
    fun staleCompletionCannotConsumeAReplacementAttempt() {
        val budget = FogSnapshotProofBudget(maxAttempts = 3)
        val first = requireNotNull(budget.begin(lifecycleEpoch = 0L, cameraEpoch = 0L))
        assertTrue(budget.abandon(first))
        val replacement = requireNotNull(budget.begin(lifecycleEpoch = 1L, cameraEpoch = 0L))

        assertFalse(budget.recordSuccess(first))
        assertTrue(budget.recordSuccess(replacement))
        assertFalse(budget.recordSuccess(replacement))
    }
}
