package app.trailveil.data.map

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ViewportRawPointMemoTest {
    private val wide = ViewportBounds(-10.0, 10.0, -10.0, 10.0)
    private val narrow = ViewportBounds(-1.0, 1.0, -1.0, 1.0)
    private fun point(id: Long, x: Double = 0.0, sequence: Long = id - 1) = ViewportTrackPoint(id, 1, 1, 0, sequence, 0.0, x)

    private class Rig(val memo: ViewportRawPointMemo, var rows: List<ViewportTrackPoint>) {
        val owner = Any()
        var queries = 0
        var stamps = 0
        var onStamp: (Int) -> Unit = {}
        var afterQuery: () -> Unit = {}
        suspend fun read(bounds: ViewportBounds, identity: Any = owner): List<ViewportTrackPoint> = memo.read(identity,
            bounds.south, bounds.north, bounds.longitudeIntervals().single(), latestPointId = {
                onStamp(++stamps); rows.maxOfOrNull { it.pointId } ?: 0L
            }, query = {
                queries++
                rows.filter { it.latitude >= bounds.south && it.latitude <= bounds.north &&
                    it.longitude >= bounds.west && it.longitude <= bounds.east }.also { afterQuery() }
            })
    }

    @Test fun completeDomainFilterKeepsTuplesAndSequenceGapsIncludingInclusiveEdges() = runBlocking {
        val rows = listOf(point(4, 1.0), point(1, -1.0), point(3, 5.0), point(2),
            ViewportTrackPoint(5, 2, 3, 7, 9, 1.0, 1.0))
        val memo = ViewportRawPointMemo({ true })
        val rig = Rig(memo, rows)
        assertEquals(rows, rig.read(wide))
        memo.seal()
        val actual = rig.read(narrow)
        val expected = rows.filter { it.longitude in -1.0..1.0 }
        assertEquals(expected, actual)
        expected.zip(actual).forEach { (a, b) -> assertSame(a, b); assertEquals(a.latitude.toRawBits(), b.latitude.toRawBits()) }
        val cachedSource = ViewportTrackDataSource { south, north, interval -> rig.read(ViewportBounds(south, north, interval.west, interval.east)) }
        val baseline = ViewportTrackDataSource { _, _, _ -> expected }.read(narrow)
        assertEquals(baseline, cachedSource.read(narrow))
        assertEquals(listOf(2, 1, 1), baseline.segments.map { it.points.size })
        assertEquals(1, rig.queries)
        memo.close()
    }

    @Test fun partialDomainWithIdenticalRowsAndDifferentDaoIdentityBothReadSql() = runBlocking {
        val memo = ViewportRawPointMemo({ true }); val rig = Rig(memo, listOf(point(1)))
        rig.read(narrow); memo.seal()
        assertEquals(rig.rows, rig.read(wide))
        assertEquals(rig.rows, rig.read(narrow, Any()))
        assertEquals(3, rig.queries)
        assertEquals(2, rig.stamps) // neither miss issues a validation query
    }

    @Test fun emptyZeroStampIsReusableButAppendInvalidatesIt() = runBlocking {
        val memo = ViewportRawPointMemo({ true }); val rig = Rig(memo, emptyList())
        rig.read(wide); memo.seal(); assertTrue(rig.read(narrow).isEmpty()); assertEquals(1, rig.queries)
        rig.rows = listOf(point(1)); assertEquals(rig.rows, rig.read(narrow)); assertEquals(2, rig.queries)
    }

    @Test fun appendDuringCaptureBetweenPhasesOrDuringHitValidationFallsBack() = runBlocking {
        for (changeAt in listOf(2, 3, 4)) {
            val memo = ViewportRawPointMemo({ true }); val rig = Rig(memo, listOf(point(1)))
            rig.onStamp = { if (it == changeAt) rig.rows = listOf(point(1), point(2)) }
            rig.read(wide); memo.seal()
            assertEquals(listOf(point(1), point(2)), rig.read(narrow))
            assertEquals("changeAt=$changeAt", 2, rig.queries)
            memo.close()
        }
    }

    @Test fun replacementWithSameMaxIdInvalidatesCaptureAndBothHitChecks() = runBlocking {
        for (changeAt in listOf(2, 3, 4)) {
            var epoch = 0L
            val memo = ViewportRawPointMemo({ epoch == 0L })
            val rig = Rig(memo, listOf(point(1, -0.5)))
            rig.onStamp = { if (it == changeAt) { epoch = 2L; rig.rows = listOf(point(1, 0.5)) } }
            rig.read(wide); memo.seal()
            assertEquals(listOf(point(1, 0.5)), rig.read(narrow)); assertEquals(2, rig.queries)
        }
        val inactive = Rig(ViewportRawPointMemo({ false }), listOf(point(1)))
        inactive.read(wide); inactive.memo.seal(); inactive.read(narrow)
        assertEquals(2, inactive.queries); assertEquals(0, inactive.stamps)
    }

    @Test fun replacementBetweenCaptureAndSealDropsRowsEvenWhenStampDoesNotChange() = runBlocking {
        var valid = true
        val memo = ViewportRawPointMemo({ valid }); val rig = Rig(memo, listOf(point(1, -0.5)))
        rig.afterQuery = { valid = false; rig.rows = listOf(point(1, 0.5)) }
        rig.read(wide); memo.seal(); rig.afterQuery = {}
        assertEquals(rig.rows, rig.read(narrow)); assertEquals(2, rig.queries)
    }

    @Test fun rowEntryAndAccountedByteCapsNeverKeepAPrefix() = runBlocking {
        for ((maxRows, bytes, hit) in listOf(Triple(2, 256L, true), Triple(1, 256L, false), Triple(2, 255L, false))) {
            val memo = ViewportRawPointMemo({ true }, maxRows = maxRows, maxAccountedBytes = bytes)
            val rig = Rig(memo, listOf(point(1), point(2)))
            rig.read(wide); memo.seal(); assertEquals(rig.rows, rig.read(narrow))
            assertEquals(if (hit) 1 else 2, rig.queries)
        }
        val memo = ViewportRawPointMemo({ true }, maxEntries = 2)
        val rig = Rig(memo, listOf(point(1)))
        rig.read(narrow); rig.read(narrow); rig.read(wide); memo.seal(); rig.read(wide)
        assertEquals(4, rig.queries); assertEquals(4, rig.stamps)
        val total = ViewportRawPointMemo({ true }, maxRows = 3)
        val limited = Rig(total, listOf(point(1), point(2)))
        limited.read(narrow); limited.read(wide); total.seal(); limited.read(wide)
        assertEquals(3, limited.queries) // second entry would exceed the aggregate row limit
    }

    @Test fun sealPreventsFurtherCaptureAndCloseDropsSnapshots() = runBlocking {
        val memo = ViewportRawPointMemo({ true }); val rig = Rig(memo, listOf(point(1)))
        rig.read(narrow); memo.seal(); rig.read(wide); rig.read(wide)
        assertEquals(3, rig.queries); assertEquals(2, rig.stamps)
        memo.close(); memo.seal(); rig.read(narrow)
        assertEquals(4, rig.queries); assertEquals(2, rig.stamps)
        val field = ViewportRawPointMemo::class.java.getDeclaredField("entries").apply { isAccessible = true }
        assertTrue((field.get(memo) as List<*>).isEmpty())
    }

    @Test fun defaultRowLimitIncludes32768AndRejects32769WithoutTruncation() = runBlocking {
        for (count in listOf(32_768, 32_769)) {
            val memo = ViewportRawPointMemo({ true })
            val rig = Rig(memo, (1L..count.toLong()).map { point(it) })
            rig.read(wide); memo.seal()
            assertEquals(count, rig.read(narrow).size)
            assertEquals(if (count == 32_768) 1 else 2, rig.queries)
            memo.close()
        }
    }

    @Test fun metadataGateUsesActualCaptureWindowAndRejectsPartialOrWrappedBeforeAnyStamp() = runBlocking {
        for ((required, capture, hit) in listOf(Triple(wide, wide, true), Triple(wide, narrow, false),
            Triple(ViewportBounds(-1.0, 1.0, 179.0, -179.0), wide, false))) {
            val memo = ViewportRawPointMemo({ true }, requiredCaptureBounds = required)
            val rig = Rig(memo, listOf(point(1)))
            rig.read(capture); memo.seal(); assertEquals(rig.rows, rig.read(narrow))
            assertEquals(if (hit) 1 else 2, rig.queries)
            assertEquals(if (hit) 4 else 0, rig.stamps)
        }
    }

    @Test fun snapshotDoesNotFollowCallerListMutation() = runBlocking {
        val mutable = mutableListOf(point(1))
        val memo = ViewportRawPointMemo({ true }); val owner = Any()
        memo.read(owner, -10.0, 10.0, LongitudeInterval(-10.0, 10.0), { 1L }, { mutable })
        mutable.clear(); memo.seal()
        assertEquals(listOf(point(1)), memo.read(owner, -1.0, 1.0, LongitudeInterval(-1.0, 1.0), { 1L }, { error("should hit") }))
    }

    @Test fun stampAndRawQueryFailuresPropagate() = runBlocking {
        val failure = IllegalStateException("storage unavailable")
        for (atStamp in listOf(false, true)) {
            val memo = ViewportRawPointMemo({ true })
            try {
                memo.read(Any(), -1.0, 1.0, LongitudeInterval(-1.0, 1.0), { if (atStamp) throw failure else 0L }, { throw failure })
                fail("failure swallowed")
            } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        }
    }

    @Test fun cancellationDuringValidationOrFilteringDoesNotReturnRowsAndFinallyCloses() {
        for (filter in listOf(false, true)) {
            val job = Job()
            val memo = ViewportRawPointMemo({ true })
            val rig = Rig(memo, (1L..1024L).map { point(it) })
            assertThrows(CancellationException::class.java) {
                runBlocking(job) {
                    try {
                        rig.read(wide); memo.seal()
                        if (filter) {
                            // Inject a read-only observing list into the private snapshot to cancel after 513 visits.
                            val entriesField = ViewportRawPointMemo::class.java.getDeclaredField("entries").apply { isAccessible = true }
                            val entry = (entriesField.get(memo) as List<*>).single()!!
                            val rowsField = entry.javaClass.getDeclaredField("rows").apply { isAccessible = true }
                            @Suppress("UNCHECKED_CAST") val original = rowsField.get(entry) as List<ViewportTrackPoint>
                            rowsField.set(entry, object : AbstractList<ViewportTrackPoint>() {
                                override val size get() = original.size
                                override fun get(index: Int): ViewportTrackPoint {
                                    if (index == 512) job.cancel()
                                    check(index < 769) { "filter did not check cancellation within one chunk" }
                                    return original[index]
                                }
                            })
                        } else rig.onStamp = { if (it == 3) job.cancel() }
                        rig.read(narrow)
                    } finally { withContext(NonCancellable) { memo.close() } }
                }
            }
            runBlocking { memo.close() }
        }
    }

    @Test fun sealAndCloseSerializeBehindAnInFlightCapture() = runBlocking {
        val memo = ViewportRawPointMemo({ true })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val read = async { memo.read(Any(), -1.0, 1.0, LongitudeInterval(-1.0, 1.0), { 0L }, {
            entered.complete(Unit); release.await(); emptyList()
        }) }
        entered.await()
        val sealed = async { memo.seal() }
        val closed = async { memo.close() }
        yield(); assertFalse(sealed.isCompleted); assertFalse(closed.isCompleted)
        release.complete(Unit); read.await(); sealed.await(); closed.await()
    }
}
