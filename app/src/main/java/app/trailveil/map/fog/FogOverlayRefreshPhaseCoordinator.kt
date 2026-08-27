package app.trailveil.map.fog

enum class FogOverlayRefreshPhase {
    GENERATION_STARTED,
    CANONICAL_PUBLISHED,
}

data class FogOverlayRefreshSnapshot(
    val generation: Long?,
    val generationStarted: Boolean,
    val canonicalPublished: Boolean,
)

/**
 * Coalesces the one SDK cache invalidation required by a covered generation.
 *
 * Generation start only records/revokes provider state: the Activity's full-screen local cover is
 * already opaque, so clearing here would make Google request a complete placeholder viewport that
 * is immediately discarded. One clear happens after canonical masks publish, so the SDK asks the
 * provider again exactly once. The state is constant-size; tile requests and point changes never
 * call the supplied clear seam directly.
 */
class FogOverlayRefreshPhaseCoordinator(
    private val clearCache: () -> Unit,
) {
    private val lock = Any()
    private var activeGeneration: Long? = null
    private var started = false
    private var published = false

    fun onGenerationStarted(generation: Long): Boolean = synchronized(lock) {
        require(generation >= 0L) { "generation must be non-negative" }
        if (activeGeneration?.let { current -> generation <= current } == true) return false
        activeGeneration = generation
        started = true
        published = false
        true
    }

    fun onCanonicalPublished(generation: Long): Boolean = synchronized(lock) {
        require(generation >= 0L) { "generation must be non-negative" }
        if (activeGeneration != generation || !started || published) return false
        published = true
        invokeClearLocked()
    }

    fun reset() = synchronized(lock) {
        activeGeneration = null
        started = false
        published = false
    }

    fun snapshot(): FogOverlayRefreshSnapshot = synchronized(lock) {
        FogOverlayRefreshSnapshot(
            generation = activeGeneration,
            generationStarted = started,
            canonicalPublished = published,
        )
    }

    /** A provider cleanup failure is recoverable; callers keep the opaque local cover visible. */
    private fun invokeClearLocked(): Boolean = try {
        clearCache()
        true
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }
}
