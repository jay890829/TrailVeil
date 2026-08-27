package app.trailveil.map.fog

/**
 * Thread-safe one-shot barrier for provider tile delivery after an SDK cache clear.
 *
 * Keys are canonical XYZ identities, so repeated Google world-copy requests satisfy the same
 * expected tile without growing state. Stale generations and unexpected keys are ignored.
 */
class FogTileRequestBarrier(
    val generation: Long,
    expectedKeys: Set<FogTileKey>,
    private val onComplete: () -> Unit,
) {
    init {
        require(generation >= 0L) { "generation must be non-negative" }
        require(expectedKeys.isNotEmpty()) { "tile request barrier must expect at least one key" }
    }

    private val remaining = expectedKeys.toMutableSet()
    private var completed = false

    fun record(requestGeneration: Long, key: FogTileKey): Boolean {
        val shouldComplete = synchronized(this) {
            if (completed || requestGeneration != generation || !remaining.remove(key)) {
                return false
            }
            if (remaining.isNotEmpty()) return true
            completed = true
            true
        }
        if (shouldComplete) onComplete()
        return true
    }

    @Synchronized
    fun remainingCount(): Int = remaining.size

    @Synchronized
    fun isComplete(): Boolean = completed
}
