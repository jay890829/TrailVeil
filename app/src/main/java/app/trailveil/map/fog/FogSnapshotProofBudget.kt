package app.trailveil.map.fog

/**
 * Provider-neutral bounded attempt budget for a visual proof.
 *
 * A lifecycle or camera transition abandons the active attempt without advancing the counter.
 * The next run therefore spends the same remaining attempt budget, while the unique token rejects
 * a callback from the abandoned snapshot.  A genuine failed observation advances exactly once.
 */
internal class FogSnapshotProofBudget(
    private val maxAttempts: Int,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    class Attempt internal constructor(
        val number: Int,
        val lifecycleEpoch: Long,
        val cameraEpoch: Long,
        val token: Long,
    )

    private var nextAttempt = 1
    private var nextToken = 0L
    private var active: Attempt? = null
    private var finished = false

    /** Claims the next attempt, or null when this proof has finished or is already in flight. */
    fun begin(lifecycleEpoch: Long, cameraEpoch: Long): Attempt? {
        if (finished || active != null) return null
        return Attempt(
            number = nextAttempt,
            lifecycleEpoch = lifecycleEpoch,
            cameraEpoch = cameraEpoch,
            token = ++nextToken,
        ).also { active = it }
    }

    fun isCurrent(
        attempt: Attempt,
        lifecycleEpoch: Long,
        cameraEpoch: Long,
    ): Boolean = active === attempt && attempt.lifecycleEpoch == lifecycleEpoch &&
        attempt.cameraEpoch == cameraEpoch && !finished

    /** Abandons an active attempt without advancing the bounded failure counter. */
    fun abandon(attempt: Attempt): Boolean {
        if (active !== attempt || finished) return false
        active = null
        return true
    }

    /** Abandons whatever is active, preserving the next attempt number. */
    fun abandonActive(): Boolean {
        if (active == null || finished) return false
        active = null
        return true
    }

    /**
     * Records one genuine failed observation. Returns null for a stale callback, true when another
     * attempt remains, and false when this failure exhausts the budget.
     */
    fun recordFailure(attempt: Attempt): Boolean? {
        if (active !== attempt || finished) return null
        active = null
        return if (attempt.number >= maxAttempts) {
            finished = true
            false
        } else {
            nextAttempt = attempt.number + 1
            true
        }
    }

    /** Completes the proof only for the active callback. */
    fun recordSuccess(attempt: Attempt): Boolean {
        if (active !== attempt || finished) return false
        active = null
        finished = true
        return true
    }
}
