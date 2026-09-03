package app.trailveil.map.fog

import kotlin.math.max

/**
 * Active-time budget for one provider operation.
 *
 * A lifecycle stop pauses the operation rather than granting a new budget when the host returns.
 * Each active run has a lease token; pausing, resuming or cancelling invalidates the old token so
 * a callback that was already in flight cannot commit into a later run.  The owner and camera epoch
 * are carried through resume so a caller cannot accidentally apply a paused result to another
 * generation or camera.
 *
 * This class deliberately has no Android or coroutine dependency.  Provider bindings use it for
 * their asynchronous render work, while JVM tests can control [nowNanos] to exercise lifecycle
 * races deterministically.
 */
internal class FogLifecycleBudget(
    totalMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    init {
        require(totalMillis > 0L) { "totalMillis must be positive" }
    }

    class Lease internal constructor(
        val owner: Long,
        val cameraEpoch: Long,
        val token: Long,
        val remainingMillis: Long,
    ) {
        fun withRemaining(remainingMillis: Long): Lease = Lease(
            owner = owner,
            cameraEpoch = cameraEpoch,
            token = token,
            remainingMillis = remainingMillis,
        )
    }

    private data class ActiveRun(
        val lease: Lease,
        val startedAtNanos: Long,
        val budgetAtStartMillis: Long,
    )

    private val lock = Any()
    private var remainingMillis = totalMillis
    private var nextToken = 0L
    private var active: ActiveRun? = null
    private var paused: Lease? = null
    private var closed = false

    /** Starts the first active run. Returns null after expiry or once this budget is closed. */
    fun start(owner: Long, cameraEpoch: Long): Lease? = synchronized(lock) {
        if (closed || active != null || paused != null || remainingMillis <= 0L) return@synchronized null
        newRunLocked(owner, cameraEpoch)
    }

    /** Pauses [lease] and returns the single-use resume token, or null when it is stale. */
    fun pause(lease: Lease): Lease? = synchronized(lock) {
        val current = active ?: return@synchronized null
        if (current.lease !== lease || closed) return@synchronized null
        updateRemainingLocked(current)
        active = null
        val resumeToken = lease.withRemaining(remainingMillis)
        paused = resumeToken
        resumeToken
    }

    /** Resumes exactly the paused owner/camera operation with its preserved remaining budget. */
    fun resume(resumeToken: Lease): Lease? = synchronized(lock) {
        if (closed || active != null || paused !== resumeToken || remainingMillis <= 0L) {
            return@synchronized null
        }
        paused = null
        newRunLocked(resumeToken.owner, resumeToken.cameraEpoch)
    }

    /** True only for the currently active, non-paused run. */
    fun isCurrent(lease: Lease): Boolean = synchronized(lock) {
        active?.lease === lease && !closed
    }

    /** Returns the budget left, accounting for active elapsed time without resetting the clock. */
    fun remainingMillis(): Long = synchronized(lock) {
        active?.let(::updateRemainingLocked)
        remainingMillis
    }

    /** Completes [lease] and closes the one-shot operation. Stale completions are rejected. */
    fun complete(lease: Lease): Boolean = synchronized(lock) {
        val current = active ?: return@synchronized false
        if (current.lease !== lease || closed) return@synchronized false
        updateRemainingLocked(current)
        active = null
        paused = null
        closed = true
        true
    }

    /** Cancels this operation. It never reports cancellation as an operation failure. */
    fun cancel(lease: Lease? = null): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (lease != null && active?.lease !== lease && paused !== lease) return@synchronized false
        active = null
        paused = null
        closed = true
        true
    }

    private fun newRunLocked(owner: Long, cameraEpoch: Long): Lease {
        val lease = Lease(
            owner = owner,
            cameraEpoch = cameraEpoch,
            token = ++nextToken,
            remainingMillis = remainingMillis,
        )
        active = ActiveRun(
            lease = lease,
            startedAtNanos = nowNanos(),
            budgetAtStartMillis = remainingMillis,
        )
        return lease
    }

    private fun updateRemainingLocked(run: ActiveRun) {
        val elapsedNanos = max(0L, nowNanos() - run.startedAtNanos)
        val elapsedMillis = elapsedNanos / NANOS_PER_MILLISECOND
        // Keep the run's starting balance separate from the shared field.  The latter may be
        // queried more than once while a run is active; subtracting elapsed time from it on every
        // query would charge the same milliseconds repeatedly.
        remainingMillis = max(0L, run.budgetAtStartMillis - elapsedMillis)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
