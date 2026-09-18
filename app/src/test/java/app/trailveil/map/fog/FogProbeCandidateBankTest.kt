package app.trailveil.map.fog

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FogProbeCandidateBankTest {
    private val key = FogTileKey(0, 0, 0, 1)
    private val request = FogViewportCoverageRequest(GeoPoint(0.0, 0.0), 0,
        GeoPoint(-85.0, -180.0), GeoPoint(85.0, -180.0),
        GeoPoint(85.0, 180.0), GeoPoint(-85.0, 180.0))
    private fun pixel(probe: FogSnapshotVisualProbe, mask: FogPixelMask): Pair<Int, Int> =
        ((probe.longitude + 180.0) / 360.0 * mask.width - 0.5).roundToInt() to
            (WebMercator.normalizedY(probe.latitude) * mask.height - 0.5).roundToInt()

    @Test fun allBanksKeepCanonicalVisibilityExclusionsStrengthAndSeparation() {
        val zones = listOf(emptyList(), listOf(FogProbeExclusionZone(-20.0, 20.0, -30.0, 30.0)),
            listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0)))
        for (pattern in 0..4) for (excluded in zones) {
            val mask = FogPixelMask(64, 64, ByteArray(4096) { i ->
                val x = i % 64; val y = i / 64
                val opaque = when (pattern) {
                    0 -> true
                    1 -> false
                    2 -> x % 5 == 0
                    3 -> (x * 17 + y * 31) % 11 < 5
                    else -> x in 15..47 && y in 7..39
                }
                if (opaque) 184.toByte() else 0
            })
            val plans = FogProbeCandidateBank.entries.map { bank ->
                FogSnapshotVisualProbePlanner(4).plan(request, mapOf(key to mask), excluded, bank)
            }
            for (plan in plans) {
                assertEquals(plans.first().coverageKeys, plan.coverageKeys)
                assertEquals(plans.first().provableKeys(), plan.provableKeys())
                assertEquals(plans.first().zoneBlockedKeys, plan.zoneBlockedKeys)
                for (block in plan.probeBlocks(key)) {
                    assertTrue(block.size in 1..4)
                    val points = block.map { pixel(it, mask) }
                    block.zip(points).forEach { (probe, p) ->
                        val (x, y) = p
                        assertTrue(x in 0 until 64 && y in 0 until 64)
                        assertTrue(mask.alphaAt(x, y) != 0)
                        assertTrue(probe.latitude in -85.0..85.0)
                        assertFalse(excluded.any { it.contains(probe.latitude, probe.longitude) })
                        assertEquals(probe.blockIndex, (y / 16) * 4 + x / 16)
                        if (probe.strongNeighbourhood) {
                            assertTrue(x in 1..62 && y in 1..62)
                            for (dy in -1..1) for (dx in -1..1)
                                assertTrue(mask.alphaAt(x + dx, y + dy) != 0)
                        }
                    }
                    for (i in points.indices) for (j in 0 until i)
                        assertTrue(maxOf(abs(points[i].first - points[j].first),
                            abs(points[i].second - points[j].second)) >= 8)
                }
            }
        }
    }

    @Test fun banksAreDistinctAndMemoRetainsExactlyTheirOwnInputsUntilClear() {
        val masks = mapOf(key to FogPixelMask(64, 64, ByteArray(4096) { 184.toByte() }))
        val memo = FogSnapshotVisualProbePlanMemo()
        val plans = FogProbeCandidateBank.entries.map { bank ->
            FogSnapshotVisualProbePlanner(4).plan(request, masks, candidateBank = bank).also {
                assertNull(memo.find(1, request, masks, emptyList(), bank))
                memo.remember(1, request, masks, emptyList(), it, bank)
            }
        }
        assertEquals(4, plans.map { it.probesByKey }.toSet().size)
        FogProbeCandidateBank.entries.forEachIndexed { i, bank ->
            assertSame(plans[i], memo.find(1, request, masks, emptyList(), bank))
            assertNull(memo.find(2, request, masks, emptyList(), bank))
            assertNull(memo.find(1, request.copy(center = GeoPoint(1.0, 0.0)), masks, emptyList(), bank))
            assertNull(memo.find(1, request, mapOf(key to FogPixelMask(64, 64, masks.getValue(key).copyAlpha())), emptyList(), bank))
        }
        memo.clear()
        FogProbeCandidateBank.entries.forEach { assertNull(memo.find(1, request, masks, emptyList(), it)) }
    }

    @Test fun partialTrapezoidAndDatelineCandidatesRemainInsideTheVisiblePolygon() {
        val cases = listOf(
            FogViewportCoverageRequest(GeoPoint(0.0, 0.0), 0, GeoPoint(-60.0, -80.0),
                GeoPoint(65.0, -40.0), GeoPoint(45.0, 70.0), GeoPoint(-30.0, 100.0)),
            FogViewportCoverageRequest(GeoPoint(0.0, 179.0), 0, GeoPoint(-35.0, 150.0),
                GeoPoint(65.0, 160.0), GeoPoint(60.0, -160.0), GeoPoint(-50.0, -150.0)),
        )
        val masks = mapOf(key to FogPixelMask(256, 256, ByteArray(65536) { 184.toByte() }))
        for (viewport in cases) {
            fun project(latitude: Double, longitude: Double): Pair<Double, Double> {
                val x = ((longitude - viewport.center.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                val radians = Math.toRadians(latitude)
                val y = kotlin.math.ln(kotlin.math.tan(Math.PI / 4.0 + radians / 2.0))
                return x to y
            }
            val polygon = viewport.visibleCorners().map { project(it.latitude, it.longitude) }
            fun inside(p: Pair<Double, Double>): Boolean {
                var result = false
                var j = polygon.lastIndex
                for (i in polygon.indices) {
                    val a = polygon[i]; val b = polygon[j]
                    if ((a.second > p.second) != (b.second > p.second) &&
                        p.first < (b.first - a.first) * (p.second - a.second) / (b.second - a.second) + a.first)
                        result = !result
                    j = i
                }
                return result
            }
            val default = FogSnapshotVisualProbePlanner().plan(viewport, masks)
            for (bank in FogProbeCandidateBank.entries) {
                val plan = FogSnapshotVisualProbePlanner().plan(viewport, masks, candidateBank = bank)
                assertTrue(plan.probesByKey.isNotEmpty())
                assertEquals(default.provableKeys(), plan.provableKeys())
                assertEquals(default.zoneBlockedKeys, plan.zoneBlockedKeys)
                if (bank == FogProbeCandidateBank.FORWARD) assertEquals(default.probesByKey, plan.probesByKey)
                plan.probesByKey.values.flatten().forEach { assertTrue("outside viewport: $bank", inside(project(it.latitude, it.longitude))) }
            }
        }
    }

    @Test fun everyTraversalCancelsWithoutPublishingAPartialPlan() {
        for (bank in FogProbeCandidateBank.entries) {
            var checks = 0
            val signal = CancellationException("bounded rows")
            val thrown = assertThrows(CancellationException::class.java) {
                FogSnapshotVisualProbePlanner(4).plan(request,
                    mapOf(key to FogPixelMask(64, 64, ByteArray(4096))), candidateBank = bank) {
                    if (++checks == 15) throw signal
                }
            }
            assertSame(signal, thrown)
            assertEquals(15, checks)
        }
    }

    @Test fun abandonedAttemptsKeepTheBankAndOnlyRealFailuresRotateIt() {
        val budget = FogSnapshotProofBudget(10)
        repeat(10) { index ->
            val abandoned = checkNotNull(budget.begin(0, 0))
            assertTrue(budget.abandon(abandoned))
            val active = checkNotNull(budget.begin(1, 1))
            assertEquals(FogProbeCandidateBank.forAttempt(abandoned.number), FogProbeCandidateBank.forAttempt(active.number))
            assertEquals(FogProbeCandidateBank.entries[index % 4], FogProbeCandidateBank.forAttempt(active.number))
            assertNull(budget.recordFailure(abandoned))
            assertEquals(index < 9, budget.recordFailure(active))
        }
        assertNull(budget.begin(1, 1))
    }
}
