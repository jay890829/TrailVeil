package app.trailveil.map.fog

/**
 * Provider-neutral lifecycle gate for asynchronous Google fog work.
 *
 * A lease captured while the Activity is active becomes invalid as soon as teardown or terminal
 * fallback starts.  Async completions must check the lease before assigning a map reference,
 * attaching listeners, publishing a generation, or revealing the basemap.
 */
internal class GoogleFogLifecycleGate {
    internal class Lease internal constructor(val epoch: Long)

    private enum class State {
        ACTIVE,
        TERMINAL_FALLBACK,
        DESTROYED,
    }

    private val lock = Any()
    private var state = State.ACTIVE
    private var epoch = 0L

    fun acquire(): Lease? = synchronized(lock) {
        if (state != State.ACTIVE) return@synchronized null
        Lease(epoch)
    }

    fun callbacksAllowed(): Boolean = synchronized(lock) {
        state == State.ACTIVE
    }

    fun isCurrent(lease: Lease): Boolean = synchronized(lock) {
        state == State.ACTIVE && lease.epoch == epoch
    }

    /** Invalidates all leases before provider resources are torn down. */
    fun enterTerminalFallback(): Boolean = synchronized(lock) {
        if (state != State.ACTIVE) return@synchronized false
        state = State.TERMINAL_FALLBACK
        epoch += 1L
        true
    }

    /** Must be called before any teardown action so late SDK callbacks fail closed. */
    fun markDestroyed() = synchronized(lock) {
        if (state == State.DESTROYED) return@synchronized
        state = State.DESTROYED
        epoch += 1L
    }
}
