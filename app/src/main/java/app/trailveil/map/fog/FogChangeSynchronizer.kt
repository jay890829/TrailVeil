package app.trailveil.map.fog

import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.data.map.PersistedTrackPointChangeFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class FogSynchronization(
    val cursor: PersistedPointCursor,
    val bootstrapped: Boolean,
    val mergedPages: Int,
    val mergedChanges: Int,
    val superseded: Boolean = false,
)

/**
 * Process-valid owner of the derived-fog cursor.
 *
 * A new process clears revision-less disk state once and snapshots Room. Re-attaching a surface
 * reuses the same cursor/cache. Incremental changes are drained in bounded pages and the cursor
 * advances only after a whole page has merged successfully.
 */
internal class FogChangeSynchronizer(
    private val pointChanges: PersistedTrackPointChangeFeed,
    private val clearDerivedCache: suspend () -> Unit,
    private val mergePersistedReveals: suspend (List<FogRevealUpdate>) -> Unit,
    private val pageSize: Int = PersistedTrackPointChangeFeed.DEFAULT_CHANGE_PAGE_SIZE,
) {
    private val mutex = Mutex()
    private var cursor: PersistedPointCursor? = null
    private val replacementEpoch = MutableStateFlow(0L)
    /** Odd while a replacement is running; even after its cache invalidation has finished. */
    val canonicalEpoch: StateFlow<Long> = replacementEpoch.asStateFlow()

    /**
     * Existing harness data replacement is not an append. Serialize it with cursor draining;
     * afterward fence old workers, clear BOTH derived representations and restart subscribers.
     * A cancelled/failed multi-transaction fixture may already have committed some rows, so its
     * finally must run too. This is process-local, not V03-008's future durable mutation epoch.
     */
    suspend fun <T> replaceCanonicalData(mutation: suspend () -> T): T = mutex.withLock {
        // Fence in-flight renders before the first transaction can change canonical rows.
        // A second publication in finally restarts subscribers after partial failure as well.
        replacementEpoch.value += 1L
        var mutationFailure: Throwable? = null
        try {
            mutation()
        } catch (failure: Throwable) {
            mutationFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                cursor = null
                try {
                    clearDerivedCache()
                } catch (failure: Throwable) {
                    val original = mutationFailure
                    if (original == null) throw failure else original.addSuppressed(failure)
                } finally {
                    replacementEpoch.value += 1L
                }
            }
        }
    }

    /** See [FogViewportCoordinator.isLockedForTesting]; the same question for this lock. */
    internal val isLockedForTesting: Boolean get() = mutex.isLocked

    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    suspend fun synchronizeTo(
        targetCursor: PersistedPointCursor? = null,
        expectedCanonicalEpoch: Long? = null,
    ): FogSynchronization = mutex.withLock {
        // A queued point notification from before a replacement cannot advance the new cursor
        // back to a deleted id, nor merge an old page into a newly cleared cache.
        if (expectedCanonicalEpoch != null && expectedCanonicalEpoch != replacementEpoch.value) {
            return@withLock FogSynchronization(cursor ?: PersistedPointCursor(0L), false, 0, 0,
                superseded = true)
        }
        val needsBootstrap = cursor == null
        try {
            var current = cursor ?: run {
                clearDerivedCache()
                pointChanges.latestCursor().also { cursor = it }
            }

            val target = targetCursor ?: current
            var mergedPages = 0
            var mergedChanges = 0
            while (current.pointId < target.pointId) {
                val page = pointChanges.readChangesAfter(current, pageSize)
                check(page.isNotEmpty()) {
                    "Canonical change feed returned an empty page before target " + target.pointId
                }
                check(page.size <= pageSize) {
                    "Canonical change feed exceeded bounded page size $pageSize"
                }
                validatePage(current, page)
                mergePersistedReveals(page.map { change -> change.toRevealUpdate() })
                current = PersistedPointCursor(page.last().point.pointId)
                cursor = current
                mergedPages += 1
                mergedChanges += page.size
            }
            FogSynchronization(
                cursor = current,
                bootstrapped = needsBootstrap,
                mergedPages = mergedPages,
                mergedChanges = mergedChanges,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            cursor = null
            runCatching { clearDerivedCache() }
            throw failure
        }
    }

    private fun validatePage(
        after: PersistedPointCursor,
        page: List<PersistedTrackPointChange>,
    ) {
        var previousId = after.pointId
        page.forEach { change ->
            check(change.point.pointId > previousId) {
                "Canonical change ids must be strictly increasing after cursor " + after.pointId
            }
            previousId = change.point.pointId
        }
    }

    private fun PersistedTrackPointChange.toRevealUpdate() = FogRevealUpdate(
        current = GeoPoint(point.latitude, point.longitude),
        previousInSegment = previousPoint?.let { previous ->
            GeoPoint(previous.latitude, previous.longitude)
        },
    )
}
