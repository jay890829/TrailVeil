package app.trailveil.map.fog

import app.trailveil.data.map.PersistedPointCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogSynchronizationRenderPolicyTest {
    @Test
    fun firstRenderIsGatedUntilColdBaselineCompletes() {
        val policy = FogSynchronizationRenderPolicy()

        assertFalse(policy.canRender())
        assertEquals(
            FogSynchronizationRenderDecision.WAIT_FOR_BASELINE,
            policy.onRevisionSynchronized(synchronization(cursor = 4L, mergedChanges = 1)),
        )
        assertFalse(policy.canRender())

        assertEquals(
            FogSynchronizationRenderDecision.RENDER_CURRENT_CAMERA,
            policy.onBaselineSynchronized(
                synchronization(cursor = 4L, bootstrapped = true, mergedChanges = 0),
            ),
        )
        assertTrue(policy.canRender())
    }

    @Test
    fun onlyRevisionsThatMergeCanonicalChangesRefreshTheGeneration() {
        val policy = FogSynchronizationRenderPolicy()
        policy.onBaselineSynchronized(
            synchronization(cursor = 8L, bootstrapped = true, mergedChanges = 0),
        )

        assertEquals(
            FogSynchronizationRenderDecision.NO_REFRESH,
            policy.onRevisionSynchronized(synchronization(cursor = 8L, mergedChanges = 0)),
        )
        assertEquals(
            FogSynchronizationRenderDecision.REFRESH_CURRENT_CAMERA,
            policy.onRevisionSynchronized(synchronization(cursor = 9L, mergedChanges = 1)),
        )
    }

    @Test
    fun warmBaselineIsAlsoAValidRenderGate() {
        val policy = FogSynchronizationRenderPolicy()

        assertEquals(
            FogSynchronizationRenderDecision.RENDER_CURRENT_CAMERA,
            policy.onBaselineSynchronized(
                synchronization(cursor = 12L, bootstrapped = false, mergedChanges = 0),
            ),
        )
        assertTrue(policy.canRender())
    }

    private fun synchronization(
        cursor: Long,
        bootstrapped: Boolean = false,
        mergedChanges: Int,
    ): FogSynchronization = FogSynchronization(
        cursor = PersistedPointCursor(cursor),
        bootstrapped = bootstrapped,
        mergedPages = if (mergedChanges == 0) 0 else 1,
        mergedChanges = mergedChanges,
    )
}
