package app.trailveil.map.fog

import app.trailveil.data.map.PersistedPointCursor
import app.trailveil.data.map.PersistedPointRevision
import app.trailveil.data.map.PersistedTrackPoint
import app.trailveil.data.map.PersistedTrackPointChange
import app.trailveil.data.map.PersistedTrackPointChangeFeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogChangeSynchronizerTest {
    @Test
    fun bootstrapClearsOnceAndWarmReattachReusesProcessCursor() = runTest {
        val feed = FakeFeed(latest = 7L)
        var clears = 0
        val synchronizer = synchronizer(feed, onClear = { clears += 1 })

        val cold = synchronizer.synchronizeTo()
        val warm = synchronizer.synchronizeTo()

        assertTrue(cold.bootstrapped)
        assertFalse(warm.bootstrapped)
        assertEquals(PersistedPointCursor(7L), warm.cursor)
        assertEquals(1, clears)
        assertEquals(emptyList<Int>(), feed.requestedLimits)
    }

    @Test
    fun coalescedBacklogDrainsInBoundedPagesAndAdvancesToActualLastRows() = runTest {
        val feed = FakeFeed(latest = 0L).apply {
            changes += (1L..257L).map(::change)
        }
        val mergedPageSizes = mutableListOf<Int>()
        val synchronizer = synchronizer(
            feed = feed,
            pageSize = 128,
            onMerge = { mergedPageSizes += it.size },
        )

        val result = synchronizer.synchronizeTo(PersistedPointCursor(257L))

        assertEquals(PersistedPointCursor(257L), result.cursor)
        assertEquals(3, result.mergedPages)
        assertEquals(257, result.mergedChanges)
        assertEquals(listOf(128, 128, 1), mergedPageSizes)
        assertEquals(listOf(128, 128, 128), feed.requestedLimits)
        assertEquals(listOf(0L, 128L, 256L), feed.requestedAfter)
    }

    @Test
    fun emptyPageBeforeTargetFailsAndForcesCanonicalRebootstrap() = runTest {
        val feed = FakeFeed(latest = 0L)
        var clears = 0
        val synchronizer = synchronizer(feed, onClear = { clears += 1 })

        var failedClosed = false
        try {
            synchronizer.synchronizeTo(PersistedPointCursor(1L))
        } catch (_: IllegalStateException) {
            failedClosed = true
        }
        assertTrue(failedClosed)
        feed.changes += change(1L)

        val recovered = synchronizer.synchronizeTo(PersistedPointCursor(1L))

        assertTrue(recovered.bootstrapped)
        assertEquals(PersistedPointCursor(1L), recovered.cursor)
        assertEquals(3, clears)
        assertEquals(listOf(0L, 0L), feed.requestedAfter)
    }

    @Test
    fun mergeFailureNeverAdvancesCursorAndRetriesTheSameCanonicalPage() = runTest {
        val feed = FakeFeed(latest = 0L).apply { changes += change(1L) }
        var mergeAttempts = 0
        val synchronizer = synchronizer(
            feed = feed,
            onMerge = {
                mergeAttempts += 1
                if (mergeAttempts == 1) error("merge failure")
            },
        )

        var failedClosed = false
        try {
            synchronizer.synchronizeTo(PersistedPointCursor(1L))
        } catch (_: IllegalStateException) {
            failedClosed = true
        }
        assertTrue(failedClosed)
        val recovered = synchronizer.synchronizeTo(PersistedPointCursor(1L))

        assertEquals(PersistedPointCursor(1L), recovered.cursor)
        assertEquals(2, mergeAttempts)
        assertEquals(listOf(0L, 0L), feed.requestedAfter)
    }

    private fun synchronizer(
        feed: PersistedTrackPointChangeFeed,
        pageSize: Int = 256,
        onClear: suspend () -> Unit = {},
        onMerge: suspend (List<FogRevealUpdate>) -> Unit = {},
    ) = FogChangeSynchronizer(
        pointChanges = feed,
        clearDerivedCache = onClear,
        mergePersistedReveals = onMerge,
        pageSize = pageSize,
    )

    private class FakeFeed(
        var latest: Long,
    ) : PersistedTrackPointChangeFeed {
        val changes = mutableListOf<PersistedTrackPointChange>()
        val requestedAfter = mutableListOf<Long>()
        val requestedLimits = mutableListOf<Int>()

        override suspend fun latestCursor() = PersistedPointCursor(latest)

        override fun revisionsAfter(
            cursor: PersistedPointCursor,
        ): Flow<PersistedPointRevision> = emptyFlow()

        override suspend fun readChangesAfter(
            cursor: PersistedPointCursor,
            limit: Int,
        ): List<PersistedTrackPointChange> {
            requestedAfter += cursor.pointId
            requestedLimits += limit
            return changes.filter { it.point.pointId > cursor.pointId }.take(limit)
        }
    }

    private companion object {
        fun change(id: Long) = PersistedTrackPointChange(
            point = PersistedTrackPoint(
                pointId = id,
                sessionId = 1L,
                segmentId = 1L,
                segmentSequence = 0L,
                pointSequence = id - 1L,
                timestamp = id,
                latitude = 25.0,
                longitude = 121.0,
            ),
            previousPoint = null,
        )
    }
}
