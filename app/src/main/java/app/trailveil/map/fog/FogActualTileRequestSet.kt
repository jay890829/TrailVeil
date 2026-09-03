package app.trailveil.map.fog

/** Coordinate-free snapshot of one renderer-observed delivery session. */
data class FogActualTileRequestSnapshot(
    val generation: Long?,
    val requestedCount: Int,
    val deliveredCount: Int,
    val overflowed: Boolean,
    val barrierArmed: Boolean,
)

/**
 * Thread-safe bridge from provider worker callbacks to [FogTileRequestBarrier]. Expected keys are
 * taken only from requests the renderer actually made after a target overlay was attached; no
 * predicted viewport or floor-zoom plan can enter this set.
 */
class FogActualTileRequestSet(
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
) {
    init {
        require(maxKeys > 0) { "maxKeys must be positive" }
    }

    private val lock = Any()
    private var generation: Long? = null
    private val requested = LinkedHashSet<FogTileKey>()
    private val delivered = LinkedHashSet<FogTileKey>()
    private var overflowed = false
    private var barrier: FogTileRequestBarrier? = null

    fun begin(targetGeneration: Long) = synchronized(lock) {
        require(targetGeneration >= 0L) { "generation must be non-negative" }
        generation = targetGeneration
        requested.clear()
        delivered.clear()
        overflowed = false
        barrier = null
    }

    /** Returns false only when the bounded session overflowed. */
    fun recordRequested(requestGeneration: Long, key: FogTileKey): Boolean = synchronized(lock) {
        if (generation != requestGeneration || overflowed) return false
        if (key !in requested && requested.size >= maxKeys) {
            overflowed = true
            barrier = null
            return false
        }
        if (requested.add(key)) {
            // A quiet-window barrier built before this late request is no longer complete enough.
            barrier = null
        }
        true
    }

    fun recordDelivered(requestGeneration: Long, key: FogTileKey): Boolean {
        val activeBarrier: FogTileRequestBarrier?
        synchronized(lock) {
            if (generation != requestGeneration || key !in requested || overflowed) return false
            delivered += key
            activeBarrier = barrier
        }
        activeBarrier?.record(requestGeneration, key)
        return true
    }

    /**
     * Freezes the current actual request set into a one-shot barrier and replays deliveries that
     * raced ahead of the main-thread quiet-window check. Returns false for empty/overflowed state.
     */
    fun armBarrier(onComplete: () -> Unit): Boolean {
        val target: Long
        val expected: Set<FogTileKey>
        val alreadyDelivered: Set<FogTileKey>
        val candidate: FogTileRequestBarrier
        synchronized(lock) {
            target = generation ?: return false
            if (overflowed || requested.isEmpty()) return false
            expected = LinkedHashSet(requested)
            alreadyDelivered = LinkedHashSet(delivered)
            candidate = FogTileRequestBarrier(target, expected, onComplete)
            barrier = candidate
        }
        alreadyDelivered.forEach { key -> candidate.record(target, key) }
        return true
    }

    fun requestedKeys(): Set<FogTileKey> = synchronized(lock) {
        LinkedHashSet(requested)
    }

    /** Atomically freezes and closes a completed session; late provider calls cannot join it. */
    fun consumeCompleted(targetGeneration: Long): Set<FogTileKey>? = synchronized(lock) {
        val currentBarrier = barrier
        if (
            generation != targetGeneration || overflowed || requested.isEmpty() ||
            currentBarrier == null || !currentBarrier.isComplete() ||
            !delivered.containsAll(requested)
        ) {
            return null
        }
        val result = LinkedHashSet(requested)
        generation = null
        requested.clear()
        delivered.clear()
        barrier = null
        result
    }

    fun snapshot(): FogActualTileRequestSnapshot = synchronized(lock) {
        FogActualTileRequestSnapshot(
            generation = generation,
            requestedCount = requested.size,
            deliveredCount = delivered.size,
            overflowed = overflowed,
            barrierArmed = barrier != null,
        )
    }

    fun cancel(targetGeneration: Long) = synchronized(lock) {
        if (generation != targetGeneration) return
        generation = null
        requested.clear()
        delivered.clear()
        overflowed = false
        barrier = null
    }

    companion object {
        const val DEFAULT_MAX_KEYS: Int = 256
    }
}
