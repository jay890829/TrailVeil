package app.trailveil.recording

import app.trailveil.data.recording.ReconcileStartingResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs app-visible STARTING repair once per process. A failed or cancelled attempt is deliberately
 * not cached, so a later screen instance can retry instead of unlocking Start on unknown state.
 */
internal class RecordingStartupReconciler(
    private val reconcile: suspend () -> ReconcileStartingResult,
) {
    private val mutex = Mutex()
    private var resolved: ReconcileStartingResult? = null

    suspend fun reconcileOnce(): ReconcileStartingResult = mutex.withLock {
        resolved ?: reconcile().also { resolved = it }
    }
}
