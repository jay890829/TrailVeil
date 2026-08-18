package app.trailveil.recording

import java.util.concurrent.atomic.AtomicLong

/**
 * Which abandoned exploration this process has already offered to re-arm.
 *
 * Process-scoped on purpose, and not held by the screen: a `remember` in a navigation destination is
 * reset by a history round trip or an activity recreation, which silently turned "one attempt" into
 * one attempt per return. A fresh process is a fresh chance; a blocked attempt inside one process
 * must not keep retrying, because the honest abandoned card it leaves on screen is already what the
 * user needs to see.
 *
 * One slot, deliberately: the claim is per session id, not a permanent ledger of every session this
 * process has seen. Returning to a session after another has been offered gives it a fresh attempt.
 * That is the intended trade — the alternative grows without bound for a guard whose only job is to
 * stop a loop within one screen's lifetime — and it is asserted rather than assumed, because this
 * lived briefly in the container where nothing could reach it and a mutation that deleted the guard
 * entirely broke no test at all.
 */
internal class AbandonedResumeClaims {
    private val claimed = AtomicLong(NOTHING_CLAIMED)

    /** True when this process should make the offer, false when it already has for that session. */
    fun claim(sessionId: Long): Boolean =
        sessionId > NOTHING_CLAIMED && claimed.getAndSet(sessionId) != sessionId

    private companion object {
        const val NOTHING_CLAIMED = 0L
    }
}
