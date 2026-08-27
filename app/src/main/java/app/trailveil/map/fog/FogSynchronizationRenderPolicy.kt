package app.trailveil.map.fog

import app.trailveil.data.map.PersistedPointCursor

/** The only render actions a fog surface may take after synchronization. */
internal enum class FogSynchronizationRenderDecision {
    WAIT_FOR_BASELINE,
    RENDER_CURRENT_CAMERA,
    REFRESH_CURRENT_CAMERA,
    NO_REFRESH,
}

/**
 * Pure coordination policy for the process-scoped canonical fog synchronizer.
 *
 * A cold process baseline clears revision-less derived state and must complete before any render.
 * Later revisions only require a new generation when at least one canonical change was merged.
 */
internal class FogSynchronizationRenderPolicy {
    private var baselineCursor: PersistedPointCursor? = null

    fun reset() {
        baselineCursor = null
    }

    fun canRender(): Boolean = baselineCursor != null

    fun onBaselineSynchronized(
        synchronization: FogSynchronization,
    ): FogSynchronizationRenderDecision {
        // The process-scoped synchronizer may already have bootstrapped for another surface.  A
        // completed cold or warm synchronizeTo() is still the baseline for this Activity.
        baselineCursor = synchronization.cursor
        return FogSynchronizationRenderDecision.RENDER_CURRENT_CAMERA
    }

    fun onRevisionSynchronized(
        synchronization: FogSynchronization,
    ): FogSynchronizationRenderDecision {
        if (!canRender()) return FogSynchronizationRenderDecision.WAIT_FOR_BASELINE
        return if (synchronization.mergedChanges > 0) {
            FogSynchronizationRenderDecision.REFRESH_CURRENT_CAMERA
        } else {
            FogSynchronizationRenderDecision.NO_REFRESH
        }
    }
}
