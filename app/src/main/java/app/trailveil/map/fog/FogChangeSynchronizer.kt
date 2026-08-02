package app.trailveil.map.fog

import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.data.map.PersistedTrackPointChangeFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class FogSynchronization(
    val cursor: PersistedPointCursor,
    val bootstrapped: Boolean,
    val mergedPages: Int,
    val mergedChanges: Int,
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

    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    suspend fun synchronizeTo(
        targetCursor: PersistedPointCursor? = null,
    ): FogSynchronization = mutex.withLock {
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
