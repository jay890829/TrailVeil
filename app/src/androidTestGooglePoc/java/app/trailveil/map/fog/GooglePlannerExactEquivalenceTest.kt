package app.trailveil.map.fog

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class GooglePlannerExactEquivalenceTest {
    @Test fun transparentRunsPreserveStrongWeakOrderAndEveryCheckpoint() {
        val key = FogTileKey(0, 0, 0, 1)
        val request = rectangleRequest(0, -180.0, -85.0, 180.0, 85.0)
        val zones = listOf(emptyList(), listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0)),
            listOf(FogProbeExclusionZone(-20.0, 20.0, -30.0, 30.0)))
        var cases = 0
        for ((width, height) in listOf(1 to 1, 2 to 7, 7 to 2, 31 to 17, 32 to 32, 65 to 33)) {
            for (pattern in 0..7) {
                val alpha = ByteArray(width * height) { index ->
                    val x = index % width; val y = index / width
                    val opaque = when (pattern) {
                        0 -> false
                        1 -> true
                        2 -> (x == 0 || x == width - 1) && (y == 0 || y == height - 1)
                        3 -> y % 2 == 0
                        4 -> x % 3 == 0
                        5 -> index == width * height - 1
                        6 -> x in (width / 2 - 1)..(width / 2 + 1) && y in (height / 2 - 1)..(height / 2 + 1)
                        else -> (x * 13 + y * 7) % 11 < 3
                    }
                    if (opaque) listOf(1, 127, 128, 184, 255)[index % 5].toByte() else 0
                }
                for (exclusions in zones) for (blocks in listOf(1, 4, 16)) {
                    samePlan(request, mapOf(key to FogPixelMask(width, height, alpha)), exclusions, blocks, "transparent-run-${cases++}")
                }
            }
        }
        assertEquals(432, cases)
    }

    @Test fun truncatedAlphaKeepsTheOriginalReadFailureAndCheckpointOrder() {
        val key = FogTileKey(0, 0, 0, 1)
        val request = rectangleRequest(0, -180.0, -85.0, 180.0, 85.0)
        for ((width, height) in listOf(1 to 1, 2 to 2, 7 to 5)) {
            for (length in 0..(width * height + 2)) for (opaque in listOf(false, true)) {
                for (blocks in listOf(1, 4)) {
                    val masks = mapOf(key to FogPixelMask(width, height, ByteArray(length) { if (opaque) 184.toByte() else 0 }))
                    var oldChecks = 0; var newChecks = 0
                    val expected = runCatching { FogSnapshotVisualProbePlannerB49Reference(blocks).plan(request, masks) { oldChecks++ } }
                    val actual = runCatching { FogSnapshotVisualProbePlanner(blocks).plan(request, masks) { newChecks++ } }
                    assertEquals(oldChecks, newChecks)
                    assertEquals(expected.isSuccess, actual.isSuccess)
                    if (expected.isSuccess) assertPlansEqual(expected.getOrThrow(), actual.getOrThrow(), "alpha-length-$length")
                    else {
                        assertArrayFailureMatches(expected.exceptionOrNull()!!, actual.exceptionOrNull()!!)
                    }
                }
            }
        }
        // Products exceeding Int.MAX_VALUE remain on the legacy access path. These widths
        // bound the work before the empty payload is first read (avoid a multi-billion-pixel row).
        for ((width, height) in listOf(65536 to 65536, 5 to Int.MAX_VALUE)) {
            val masks = mapOf(key to FogPixelMask(width, height, ByteArray(0)))
            var oldChecks = 0; var newChecks = 0
            val expected = runCatching { FogSnapshotVisualProbePlannerB49Reference(1).plan(request, masks) { oldChecks++ } }
            val actual = runCatching { FogSnapshotVisualProbePlanner(1).plan(request, masks) { newChecks++ } }
            assertTrue(expected.isFailure)
            assertEquals(oldChecks, newChecks)
            assertArrayFailureMatches(expected.exceptionOrNull()!!, actual.exceptionOrNull()!!)
        }
    }

    @Test fun callbackPixelMutationsAndCancellationDoNotReuseZeroSummaries() {
        val key = FogTileKey(0, 0, 0, 1)
        val request = rectangleRequest(0, -180.0, -85.0, 180.0, 85.0)
        val results = mutableListOf<Pair<FogSnapshotVisualProbePlan, Int>>()
        for (reference in listOf(true, false)) {
            val alpha = ByteArray(32 * 32)
            val masks = mapOf(key to FogPixelMask(32, 32, alpha))
            var checks = 0
            val callback = {
                when (++checks) {
                    10 -> alpha.fill(184.toByte())
                    14 -> alpha.fill(0)
                    18 -> alpha[alpha.lastIndex] = 128.toByte()
                }
                Unit
            }
            val plan = if (reference) FogSnapshotVisualProbePlannerB49Reference(4).plan(request, masks, checkActive = callback)
                else FogSnapshotVisualProbePlanner(4).plan(request, masks, checkActive = callback)
            assertTrue(checks >= 18)
            results += plan to checks
        }
        val referenceProbes = results[0].first.probesByKey.values.flatten()
        assertTrue(referenceProbes.any { it.strongNeighbourhood })
        assertTrue(referenceProbes.any { !it.strongNeighbourhood })
        assertEquals(results[0].second, results[1].second)
        assertPlansEqual(results[0].first, results[1].first, "callback-mutates-live-bytes")
        val sentinel = CancellationException("transparent row boundary")
        for (reference in listOf(true, false)) {
            var checks = 0
            val masks = mapOf(key to FogPixelMask(32, 32, ByteArray(32 * 32)))
            val callback = { if (++checks == 25) throw sentinel; Unit }
            val thrown = assertThrows(CancellationException::class.java) {
                if (reference) FogSnapshotVisualProbePlannerB49Reference(4).plan(request, masks, checkActive = callback)
                else FogSnapshotVisualProbePlanner(4).plan(request, masks, checkActive = callback)
            }
            assertSame(sentinel, thrown)
            assertEquals(25, checks)
        }
    }
    @Test fun excludedMalformedMaskAndCallbackFailurePreserveRecovery() {
        val key = FogTileKey(0, 0, 0, 1)
        val request = rectangleRequest(0, -180.0, -85.0, 180.0, 85.0)
        val exclusions = listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0))
        val malformed = mapOf(key to FogPixelMask(2, 2, ByteArray(3) { 184.toByte() }))
        val oldFailure = runCatching { FogSnapshotVisualProbePlannerB49Reference(4).plan(request, malformed, exclusions) }.exceptionOrNull()
        val newFailure = runCatching { FogSnapshotVisualProbePlanner(4).plan(request, malformed, exclusions) }.exceptionOrNull()
        assertNotNull(oldFailure)
        assertEquals(oldFailure!!::class.java, newFailure!!::class.java)
        assertEquals(oldFailure.message, newFailure.message)

        val masks = mapOf(key to FogPixelMask(32, 32, ByteArray(32 * 32) { 184.toByte() }))
        val planner = FogSnapshotVisualProbePlanner(4)
        var checks = 0
        val sentinel = IllegalStateException("test callback after prior blocks")
        val thrown = assertThrows(IllegalStateException::class.java) {
            planner.plan(request, masks, exclusions) { if (++checks == 10) throw sentinel }
        }
        assertSame(sentinel, thrown)
        assertEquals(10, checks)
        val nextExclusions = listOf(FogProbeExclusionZone(-5.0, 5.0, -20.0, 20.0))
        var oldChecks = 0
        var newChecks = 0
        val expected = FogSnapshotVisualProbePlannerB49Reference(4).plan(request, masks, nextExclusions) { oldChecks++ }
        val actual = planner.plan(request, masks, nextExclusions) { newChecks++ }
        assertEquals(oldChecks, newChecks)
        assertPlansEqual(expected, actual, "replan-after-callback-failure")
    }

    @Test fun exclusionEdgesKeepExactOrderedPlansAcrossAxisCacheLimits() {
        var cases = 0
        val dimensions = listOf(1 to 1, 16 to 11, 31 to 17, 1024 to 1, 1025 to 1, 1 to 1025)
        for (key in listOf(FogTileKey(0, 0, 0, 1), FogTileKey(2, 1, 1, 1), FogTileKey(2, 3, 1, 1))) {
            val bounds = FogPocTileGrid.bounds(key)
            val request = rectangleRequest(key.zoom, bounds.westLongitude, bounds.southLatitude, bounds.eastLongitude, bounds.northLatitude)
            val n = 1 shl key.zoom
            for ((width, height) in dimensions) for (pattern in 0..1) {
                val mask = FogPixelMask(width, height, ByteArray(width * height) {
                    if (pattern == 0 || it % 7 != 0) 184.toByte() else 0
                })
                for (position in 0..2) {
                    val x = position * (width - 1) / 2
                    val y = position * (height - 1) / 2
                    val latitude = WebMercator.latitudeAtNormalizedY((key.y + (y + 0.5) / height) / n)
                    val longitude = (key.x + (x + 0.5) / width) / n * 360.0 - 180.0
                    // Immediate longitude ULPs can collapse through wrap/span arithmetic.
                    // Also prove two edges actually straddle the target after contains().
                    val step = 4 * maxOf(Math.ulp(longitude), Math.ulp(360.0))
                    val straddles = listOf(longitude - step, longitude + step).map {
                        FogProbeExclusionZone(-90.0, 90.0, -180.0, it)
                    }
                    assertEquals(listOf(false, true), straddles.map { it.contains(latitude, longitude) })
                    val zones = listOf(Math.nextDown(latitude), latitude, Math.nextUp(latitude)).map {
                        FogProbeExclusionZone(-90.0, it, -180.0, 180.0)
                    } + listOf(Math.nextDown(longitude), longitude, Math.nextUp(longitude)).map {
                        FogProbeExclusionZone(-90.0, 90.0, -180.0, it)
                    } + straddles
                    for (zone in zones) samePlan(request, mapOf(key to mask), listOf(zone), blocks = 4, label = "exclusion-edge-${cases++}")
                }
            }
        }
        assertEquals(864, cases)
        println("V3_ORACLE exclusionEdgeCases=$cases cacheLimit=1024")
    }

    @Test fun finiteAdversarialQuadsPreserveEveryOrderedPlanFieldAndCheckpoint() {
        val rectangle = listOf(-0.8 to 0.6, -0.8 to -0.6, 0.8 to -0.6, 0.8 to 0.6)
        val trapezoid = listOf(-0.4 to 0.5, -1.1 to -0.9, 1.2 to -0.8, 0.6 to 0.5)
        val shapes = listOf(rectangle, rectangle.reversed(), trapezoid, trapezoid.reversed(),
            listOf(-0.8 to 0.6, 0.8 to -0.6, -0.8 to -0.6, 0.8 to 0.6),
            listOf(-0.8 to 0.6, -0.8 to 0.6, 0.8 to -0.6, 0.8 to 0.6),
            listOf(-0.8 to 0.0, -0.2 to 0.0, 0.2 to 0.0, 0.8 to 0.0),
            listOf(-0.8 to 0.6, -0.8 to -0.6, 0.0 to 0.0, 0.8 to 0.6))
        val centers = listOf(GeoPoint(-0.0, -0.0), GeoPoint(25.0, 179.99), GeoPoint(78.0, -179.99))
        val zones = listOf(emptyList(), listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0)),
            listOf(FogProbeExclusionZone(-45.0, 85.0, 175.0, -175.0), FogProbeExclusionZone(-0.1, 0.1, -1.0, 1.0)))
        var cases = 0
        for (zoom in listOf(0, 2, 12)) for (center in centers) for ((shapeIndex, shape) in shapes.withIndex()) {
            val n = 1 shl zoom
            val cx = (center.longitude + 180.0) / 360.0 * n
            val cy = WebMercator.normalizedY(center.latitude) * n
            val corners = shape.map { (dx, dy) -> GeoPoint(WebMercator.latitudeAtNormalizedY((cy + dy) / n), (cx + dx) / n * 360.0 - 180.0) }
            val request = FogViewportCoverageRequest(center, zoom, corners[0], corners[1], corners[2], corners[3])
            val keys = FogViewportTileGrid.around(center, zoom, FogRenderVersions.CURRENT, paddingTiles = 1).reversed()
            val dimensions = listOf(8 to 11, 31 to 17, 32 to 32)[shapeIndex % 3]
            val masks = LinkedHashMap<FogTileKey, FogPixelMask>()
            keys.forEachIndexed { keyIndex, key ->
                val (width, height) = dimensions
                val alpha = ByteArray(width * height) { pixel -> when (shapeIndex % 3) {
                    0 -> 184.toByte()
                    1 -> 0
                    else -> if ((pixel * 13 + pixel / width * 7 + keyIndex) % 11 < 4) 0 else 1
                } }
                masks[key] = FogPixelMask(width, height, alpha)
            }
            for (exclusions in zones) {
                samePlan(request, masks, exclusions, listOf(1, 4, 16)[shapeIndex % 3], "quad-${cases++}")
            }
        }
        assertEquals(216, cases)
        println("U3_ORACLE quadCases=$cases")
    }

    @Test fun pixelCenterUlpsSignedZeroDatelineAndFullWorldEdgesRemainExact() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        var cases = 0
        for (pattern in 0..1) {
            val mask = FogPixelMask(16, 16, ByteArray(256) { if (pattern == 0 || it % 7 != 0) 0xff.toByte() else 0 })
            for (pixel in listOf(0, 7, 15)) {
                val longitude = (key.x + (pixel + 0.5) / 16) / 4 * 360 - 180
                for (edge in listOf(Math.nextDown(longitude), longitude, Math.nextUp(longitude))) {
                    samePlan(rectangleRequest(2, edge, bounds.southLatitude, bounds.eastLongitude, bounds.northLatitude), mapOf(key to mask), label = "x-ulp-${cases++}")
                }
                val latitude = WebMercator.latitudeAtNormalizedY((key.y + (pixel + 0.5) / 16) / 4)
                for (edge in listOf(Math.nextDown(latitude), latitude, Math.nextUp(latitude))) {
                    samePlan(rectangleRequest(2, bounds.westLongitude, bounds.southLatitude, bounds.eastLongitude, edge), mapOf(key to mask), label = "y-ulp-${cases++}")
                }
            }
        }
        val worldKey = FogTileKey(0, 0, 0, FogRenderVersions.CURRENT)
        val worldMask = FogPixelMask(13, 11, ByteArray(143) { 0xff.toByte() })
        for (span in listOf(Math.nextDown(360.0), 360.0, Math.nextUp(360.0), 720.0)) {
            for (shift in listOf(-360.0, 0.0, 360.0)) {
                val request = FogViewportCoverageRequest(GeoPoint(-0.0, shift), 0,
                    GeoPoint(-0.0, shift - span / 2), GeoPoint(0.0, shift - span / 2),
                    GeoPoint(0.0, shift + span / 2), GeoPoint(-0.0, shift + span / 2))
                samePlan(request, mapOf(worldKey to worldMask), label = "world-${cases++}")
            }
        }
        val datelineRequest = rectangleRequest(2, 179.0, -20.0, 181.0, 20.0)
        val datelineMasks = linkedMapOf(FogTileKey(2, 3, 1, 1) to worldMask, FogTileKey(2, 0, 2, 1) to worldMask)
        samePlan(datelineRequest, datelineMasks, listOf(FogProbeExclusionZone(-1.0, 1.0, 179.5, -179.5)), label = "wrapped-exclusion")
        assertEquals(48, cases)
        println("U3_ORACLE edgeCases=${cases + 1}")
    }

    @Test fun invalidRequestsKeepTheSameExceptionAndDoNotReturnPartialPlans() {
        val ordinary = rectangleRequest(2, -60.0, -20.0, 60.0, 20.0)
        val mask = FogPixelMask(4, 4, ByteArray(16) { 1 })
        val validKey = FogTileKey(2, 1, 1, 1)
        val cases = listOf(
            ordinary to emptyMap(),
            ordinary to mapOf(FogTileKey(1, 0, 0, 1) to mask),
            ordinary to mapOf(validKey to FogPixelMask(0, 1, ByteArray(0))),
            ordinary.copy(center = GeoPoint(0.0, Double.MAX_VALUE), floorZoom = 22) to mapOf(FogTileKey(22, 0, 0, 1) to mask),
            ordinary.copy(center = GeoPoint(0.0, -Double.MAX_VALUE), floorZoom = 0,
                nearLeft = GeoPoint(0.0, Double.MAX_VALUE)) to mapOf(FogTileKey(0, 0, 0, 1) to mask),
        )
        for ((request, masks) in cases) {
            val expected = assertThrows(IllegalArgumentException::class.java) { FogSnapshotVisualProbePlannerB49Reference().plan(request, masks) }
            val actual = assertThrows(IllegalArgumentException::class.java) { FogSnapshotVisualProbePlanner().plan(request, masks) }
            assertEquals(expected.message, actual.message)
        }
        // Finite centre projection with saturated world-column arithmetic also keeps its old result.
        samePlan(ordinary.copy(center = GeoPoint(0.0, 1e15), floorZoom = 0), mapOf(FogTileKey(0, 0, 0, 1) to mask), label = "finite-extreme")
    }

    @Test fun projectedPixelEdgesActuallyStraddleBothAxes() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        // Fix the centre so changing an edge cannot change its projection origin.
        val center = GeoPoint(0.0, -45.0)
        val centerX = (center.longitude + 180.0) / 360.0 * 4
        var cases = 0
        for (pixel in listOf(0, 7, 15)) {
            val target = 1 + (pixel + 0.5) / 16
            val longitude = target / 4 * 360 - 180
            val latitude = WebMercator.latitudeAtNormalizedY(target / 4)
            val xEdges = projectedSides(longitude, target) { edge ->
                centerX + WebMercator.wrapLongitude(edge - center.longitude) / 360.0 * 4
            }
            val yEdges = projectedSides(latitude, target) { edge -> WebMercator.normalizedY(edge) * 4 }
            for (pattern in 0..1) {
                val mask = FogPixelMask(16, 16, ByteArray(256) { if (pattern == 0 || it % 7 != 0) 0xff.toByte() else 0 })
                for (edge in xEdges) {
                    val request = rectangleRequest(2, edge, bounds.southLatitude, bounds.eastLongitude, bounds.northLatitude).copy(center = center)
                    samePlan(request, mapOf(key to mask), label = "projected-x-${cases++}")
                }
                for (edge in yEdges) {
                    val request = rectangleRequest(2, bounds.westLongitude, bounds.southLatitude, bounds.eastLongitude, edge).copy(center = center)
                    samePlan(request, mapOf(key to mask), label = "projected-y-${cases++}")
                }
            }
        }
        assertEquals(36, cases)
        println("U3_ORACLE projectedEdgeCases=$cases axes=2 sides=below,equal,above fixedCenter=true")
    }

    private fun projectedSides(seed: Double, target: Double, project: (Double) -> Double): List<Double> {
        val found = linkedMapOf<Int, Double>()
        fun inspect(value: Double) {
            found.putIfAbsent(project(value).compareTo(target), value)
        }
        inspect(seed)
        var lower = seed
        var upper = seed
        repeat(2048) {
            lower = Math.nextDown(lower)
            upper = Math.nextUp(upper)
            inspect(lower)
            inspect(upper)
            if (found.size == 3) {
                val result = listOf(found.getValue(-1), found.getValue(0), found.getValue(1))
                assertEquals(listOf(-1, 0, 1), result.map { project(it).compareTo(target) })
                assertEquals(target.toRawBits(), project(result[1]).toRawBits())
                return result
            }
        }
        fail("Cannot straddle projected edge $target: found sides ${found.keys}")
        return emptyList()
    }

    @Test fun wholeWorldCancellationRetainsThe6154And201Checkpoints() {
        val request = rectangleRequest(2, -180.0, -70.0, 180.0, 70.0)
        val masks = (1..2).flatMap { y -> (0..3).map { x -> FogTileKey(2, x, y, FogRenderVersions.CURRENT) to
            FogPixelMask(128, 128, ByteArray(128 * 128) { 0xff.toByte() }) } }.toMap()
        val exclusions = listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0))
        var expectedChecks = 0
        val expected = FogSnapshotVisualProbePlannerB49Reference().plan(request, masks, exclusions) { expectedChecks++ }
        var actualChecks = 0
        val actual = FogSnapshotVisualProbePlanner().plan(request, masks, exclusions) { actualChecks++ }
        assertEquals(6154, expectedChecks)
        assertEquals(expectedChecks, actualChecks)
        assertPlansEqual(expected, actual, "full-world-cancel-control")
        val cancelled = CancellationException("after 200 active checks")
        for (reference in listOf(true, false)) {
            var checks = 0
            val callback = { if (++checks == 201) throw cancelled; Unit }
            val thrown = assertThrows(CancellationException::class.java) {
                if (reference) FogSnapshotVisualProbePlannerB49Reference().plan(request, masks, exclusions, callback)
                else FogSnapshotVisualProbePlanner().plan(request, masks, exclusions, checkActive = callback)
            }
            assertSame(cancelled, thrown)
            assertEquals(201, checks)
        }
    }

    private fun assertArrayFailureMatches(expected: Throwable, actual: Throwable) {
        if (java.lang.Boolean.getBoolean("trailveil.strictArrayMessages")) {
            assertNotNull(expected.message)
            assertNotNull(actual.message)
        }
        assertEquals(expected::class.java, actual::class.java)
        if (expected.message != null && actual.message != null) {
            assertEquals(expected.message, actual.message)
        } else {
            // HotSpot may reuse a hot AIOOB with both message and trace omitted. The dedicated
            // -XX:-OmitStackTraceInFastThrow control compares every message with omission disabled.
            // No other missing-message failure is accepted by this repeated-array-error oracle.
            for (failure in listOf(expected, actual)) if (failure.message == null) {
                assertTrue(failure is ArrayIndexOutOfBoundsException)
                assertTrue(failure.stackTrace.isEmpty())
            }
        }
    }
    private fun samePlan(request: FogViewportCoverageRequest, masks: Map<FogTileKey, FogPixelMask>, exclusions: List<FogProbeExclusionZone> = emptyList(), blocks: Int = 16, label: String) {
        var oldChecks = 0
        var newChecks = 0
        val expected = FogSnapshotVisualProbePlannerB49Reference(blocks).plan(request, masks, exclusions) { oldChecks++ }
        val actual = FogSnapshotVisualProbePlanner(blocks).plan(request, masks, exclusions) { newChecks++ }
        assertEquals("$label checkpoints", oldChecks, newChecks)
        assertPlansEqual(expected, actual, label)
    }

    private fun assertPlansEqual(expected: FogSnapshotVisualProbePlan, actual: FogSnapshotVisualProbePlan, label: String) {
        assertEquals("$label coverage order", expected.coverageKeys.toList(), actual.coverageKeys.toList())
        assertEquals("$label key order", expected.probesByKey.keys.toList(), actual.probesByKey.keys.toList())
        assertEquals("$label blocked order", expected.zoneBlockedKeys.toList(), actual.zoneBlockedKeys.toList())
        assertEquals(expected.isProvable(), actual.isProvable())
        assertEquals(expected.provableKeys().toList(), actual.provableKeys().toList())
        for (key in expected.coverageKeys) {
            val old = expected.probesByKey[key].orEmpty()
            val new = actual.probesByKey[key].orEmpty()
            assertEquals("$label probe count", old.size, new.size)
            old.zip(new).forEach { (a, b) ->
                assertEquals(a.key, b.key)
                assertEquals("$label latitude bits", a.latitude.toRawBits(), b.latitude.toRawBits())
                assertEquals("$label longitude bits", a.longitude.toRawBits(), b.longitude.toRawBits())
                assertEquals(a.strongNeighbourhood, b.strongNeighbourhood)
                assertEquals(a.blockIndex, b.blockIndex)
            }
            assertEquals("$label block candidate order", expected.probeBlocks(key), actual.probeBlocks(key))
        }
    }

    private fun rectangleRequest(zoom: Int, west: Double, south: Double, east: Double, north: Double) = FogViewportCoverageRequest(
        GeoPoint((south + north) / 2, (west + east) / 2), zoom,
        GeoPoint(south, west), GeoPoint(north, west), GeoPoint(north, east), GeoPoint(south, east),
    )
}




