package app.trailveil.feature.recording

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Execute the action that owns a process-scoped abandoned-session claim.
 *
 * A composition can disappear after [abandonedExplorationAction] takes the claim but before the
 * durable operation commits. A cancelled attempt is not an attempt: it must return the claim so a
 * recreated screen can continue. Restart interruption is different from Resume UI work — it starts
 * no Activity or service, so once selected it safely finishes outside the composition's lifetime.
 */
internal suspend fun runClaimedAbandonedAction(
    action: AbandonedExplorationAction?,
    resume: suspend (AbandonedExplorationAction.Resume) -> Unit,
    interrupt: suspend (AbandonedExplorationAction.Interrupt) -> Boolean,
    release: (Long) -> Boolean,
) {
    var actionSettled = action == null
    try {
        when (action) {
            null -> Unit
            is AbandonedExplorationAction.Resume -> {
                resume(action)
                // Blocked and launch-failed resumes are real attempts. Keeping the claim prevents
                // an actionable blocker from becoming an automatic retry loop.
                actionSettled = true
            }
            is AbandonedExplorationAction.Interrupt -> withContext(NonCancellable) {
                actionSettled = interrupt(action)
                if (!actionSettled) release(action.sessionId)
            }
        }
    } catch (cancelled: CancellationException) {
        if (!actionSettled) {
            when (action) {
                is AbandonedExplorationAction.Resume -> release(action.sessionId)
                is AbandonedExplorationAction.Interrupt -> release(action.sessionId)
                null -> Unit
            }
        }
        throw cancelled
    }
}
