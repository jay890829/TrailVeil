package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogSnapshotVisualProbeTest {
    @Test
    fun visibleUnknownTileProducesStrongSpreadProbes() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val mask = opaqueMask()

        val plan = FogSnapshotVisualProbePlanner().plan(request(bounds), mapOf(key to mask))

        assertEquals(setOf(key), plan.coverageKeys)
        assertTrue(plan.probesByKey.getValue(key).size > 1)
        assertTrue(plan.probesByKey.getValue(key).all { probe -> probe.strongNeighbourhood })
    }

    @Test
    fun fullyExploredVisibleTileNeedsNoOpaqueVisualProof() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val clear = FogPixelMask(256, 256, ByteArray(256 * 256))

        val plan = FogSnapshotVisualProbePlanner().plan(request(bounds), mapOf(key to clear))

        assertEquals(setOf(key), plan.coverageKeys)
        assertTrue(plan.probesByKey.isEmpty())
    }

    @Test
    fun exploredCorridorLeavesOpaqueProbeCandidatesOnBothSides() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val alpha = ByteArray(256 * 256) { 0xff.toByte() }
        for (y in 120..136) {
            for (x in 0 until 256) alpha[y * 256 + x] = 0
        }

        val plan = FogSnapshotVisualProbePlanner().plan(
            request(bounds),
            mapOf(key to FogPixelMask(256, 256, alpha)),
        )

        val probes = plan.probesByKey.getValue(key)
        assertTrue(probes.size > 1)
        assertFalse(probes.all { probe -> probe.latitude == probes.first().latitude })
    }

    // ---- carry-forward F: interchangeable candidates per block ---------------------------------

    @Test
    fun everyBlockCarriesSeveralSeparatedCandidates() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val plan = FogSnapshotVisualProbePlanner().plan(
            request(FogPocTileGrid.bounds(key)),
            mapOf(key to opaqueMask()),
        )

        val blocks = plan.probeBlocks(key)
        assertEquals(
            "a fully unexplored tile yields one block per planner cell",
            FogSnapshotVisualProbePlanner.DEFAULT_BLOCKS_PER_AXIS *
                FogSnapshotVisualProbePlanner.DEFAULT_BLOCKS_PER_AXIS,
            blocks.size,
        )
        assertTrue(
            "one candidate per block is the defect carry-forward F names",
            blocks.all { candidates -> candidates.size > 1 },
        )
        assertTrue(
            "no block may exceed the candidate budget",
            blocks.all { candidates ->
                candidates.size <= FogSnapshotVisualProbePlanner.CANDIDATES_PER_BLOCK
            },
        )
        assertTrue(
            "candidates of one block must be distinct points, or they are not fallbacks",
            blocks.all { candidates ->
                candidates.map { it.latitude to it.longitude }.toSet().size == candidates.size
            },
        )
    }

    @Test
    fun candidatesOfOneBlockShareThatBlockAndBlocksDoNotShareCandidates() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val plan = FogSnapshotVisualProbePlanner().plan(
            request(FogPocTileGrid.bounds(key)),
            mapOf(key to opaqueMask()),
        )

        val blocks = plan.probeBlocks(key)
        assertTrue(
            "grouping must follow blockIndex, not list position",
            blocks.all { candidates ->
                candidates.map(FogSnapshotVisualProbe::blockIndex).toSet().size == 1
            },
        )
        val indices = blocks.map { candidates -> candidates.first().blockIndex }
        assertEquals("each block appears exactly once", indices.size, indices.toSet().size)
        assertEquals(
            "no probe may be dropped by the grouping",
            plan.probesByKey.getValue(key).size,
            blocks.sumOf { candidates -> candidates.size },
        )
    }

    /**
     * The whole point of the fallbacks: adjacent pixels are under the same label glyph, so
     * candidates that are not spread across the block would not survive anything the first one
     * did not.
     */
    @Test
    fun candidatesOfOneBlockAreSeparatedAcrossIt() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val plan = FogSnapshotVisualProbePlanner().plan(
            request(FogPocTileGrid.bounds(key)),
            mapOf(key to opaqueMask()),
        )

        // A 256 px mask over 16 blocks gives 16 px blocks and a half-block separation of 8 px.
        // Latitude is not linear in pixels, so the comparison is made in projected space: at
        // zoom 2 the world is 4 x 256 pixels on each axis.
        val worldPixels = 4.0 * 256.0
        plan.probeBlocks(key).forEach { candidates ->
            candidates.forEachIndexed { index, probe ->
                candidates.drop(index + 1).forEach { other ->
                    val dx = kotlin.math.abs(probe.longitude - other.longitude) /
                        360.0 * worldPixels
                    val dy = kotlin.math.abs(
                        WebMercator.normalizedY(probe.latitude) -
                            WebMercator.normalizedY(other.latitude),
                    ) * worldPixels
                    assertTrue(
                        "candidates are crowded into what one glyph covers: dx=$dx dy=$dy",
                        maxOf(dx, dy) >= 8.0 - TOLERANCE_PIXELS,
                    )
                }
            }
        }
    }

    @Test
    fun aBlockWithASinglePixelOfCanonicalAreaStillContributesIt() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val alpha = ByteArray(256 * 256)
        // One opaque pixel, in the middle of the tile, weak (no opaque neighbourhood).
        alpha[130 * 256 + 130] = 0xff.toByte()

        val plan = FogSnapshotVisualProbePlanner().plan(
            request(FogPocTileGrid.bounds(key)),
            mapOf(key to FogPixelMask(256, 256, alpha)),
        )

        val blocks = plan.probeBlocks(key)
        assertEquals("exactly the one block that has canonical area", 1, blocks.size)
        assertEquals("and it can only offer the pixel it has", 1, blocks.single().size)
        assertFalse(blocks.single().single().strongNeighbourhood)
    }

    // ---- exclusion zones (V02-005 design §2.3) -----------------------------------------------

    @Test
    fun probesAvoidExclusionZonesWhileTheTileStaysProven() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        // A zone over the WESTERN half of the tile: probes must come from the eastern half.
        val zone = FogProbeExclusionZone(
            southLatitude = bounds.southLatitude,
            northLatitude = bounds.northLatitude,
            westLongitude = bounds.westLongitude,
            eastLongitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
        )

        val plan = FogSnapshotVisualProbePlanner().plan(
            request(bounds),
            mapOf(key to opaqueMask()),
            exclusionZones = listOf(zone),
        )

        val probes = plan.probesByKey.getValue(key)
        assertTrue(probes.isNotEmpty())
        assertTrue(
            "every planned probe sits outside the zone",
            probes.none { probe -> zone.contains(probe.latitude, probe.longitude) },
        )
        assertTrue(plan.zoneBlockedKeys.isEmpty())
    }

    @Test
    fun aTileEntirelyEatenByZonesStaysRequiredAsZoneBlocked() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val everything = FogProbeExclusionZone(
            southLatitude = -90.0,
            northLatitude = 90.0,
            westLongitude = -180.0,
            eastLongitude = 180.0,
        )

        val plan = FogSnapshotVisualProbePlanner().plan(
            request(bounds),
            mapOf(key to opaqueMask()),
            exclusionZones = listOf(everything),
        )

        // Bounded-exclusion rule: the tile may not silently become proof-exempt.
        assertTrue(plan.probesByKey.isEmpty())
        assertEquals(setOf(key), plan.zoneBlockedKeys)
        assertFalse(
            "a fully zone-eaten viewport must not be provable — a pass would verify zero pixels",
            plan.isProvable(),
        )
    }

    @Test
    fun `a plan with usable probes everywhere is provable`() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val plan = FogSnapshotVisualProbePlanner().plan(
            request(bounds),
            mapOf(key to opaqueMask()),
            exclusionZones = listOf(
                FogProbeExclusionZone(
                    southLatitude = bounds.southLatitude,
                    northLatitude = bounds.northLatitude,
                    westLongitude = bounds.westLongitude,
                    eastLongitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
                ),
            ),
        )

        assertTrue(plan.isProvable())
        assertEquals(setOf(key), plan.provableKeys())
    }

    @Test
    fun aFullyExploredTileIsNeverReportedZoneBlocked() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val clear = FogPixelMask(256, 256, ByteArray(256 * 256))
        val everything = FogProbeExclusionZone(
            southLatitude = -90.0,
            northLatitude = 90.0,
            westLongitude = -180.0,
            eastLongitude = 180.0,
        )

        val plan = FogSnapshotVisualProbePlanner().plan(
            request(bounds),
            mapOf(key to clear),
            exclusionZones = listOf(everything),
        )

        assertTrue(plan.probesByKey.isEmpty())
        assertTrue("no opaque pixels means nothing was zone-eaten", plan.zoneBlockedKeys.isEmpty())
    }

    @Test
    fun exclusionPlanningIsDeterministicOnSeededMasks() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val seeded = java.util.Random(20260828L)
        val alpha = ByteArray(256 * 256) {
            if (seeded.nextInt(4) == 0) 0 else 0xff.toByte()
        }
        val mask = FogPixelMask(256, 256, alpha)
        val zone = FogProbeExclusionZone(
            southLatitude = bounds.southLatitude,
            northLatitude = (bounds.southLatitude + bounds.northLatitude) / 2.0,
            westLongitude = bounds.westLongitude,
            eastLongitude = bounds.eastLongitude,
        )

        val first = FogSnapshotVisualProbePlanner().plan(
            request(bounds),
            mapOf(key to mask),
            exclusionZones = listOf(zone),
        )
        val second = FogSnapshotVisualProbePlanner().plan(
            request(bounds),
            mapOf(key to mask),
            exclusionZones = listOf(zone),
        )

        assertEquals(
            first.probesByKey.mapValues { (_, probes) -> probes.map { it.latitude to it.longitude } },
            second.probesByKey.mapValues { (_, probes) -> probes.map { it.latitude to it.longitude } },
        )
        assertEquals(first.zoneBlockedKeys, second.zoneBlockedKeys)
    }

    @Test
    fun oracleIntegrityNoUnexploredVisibleTileEscapesBothProbeAndBlockedSets() {
        // The completeness invariant behind the injected-bare-basemap control: with zones
        // active, every visible tile carrying opaque canonical pixels appears in probesByKey OR
        // zoneBlockedKeys, so a bare-basemap frame always has a probe (or a blocked tile) left
        // to fail the proof — exclusions can never blind the oracle.
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val halves = listOf(
            FogProbeExclusionZone(
                southLatitude = bounds.southLatitude,
                northLatitude = bounds.northLatitude,
                westLongitude = bounds.westLongitude,
                eastLongitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
            ),
            FogProbeExclusionZone(
                southLatitude = bounds.southLatitude,
                northLatitude = bounds.northLatitude,
                westLongitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
                eastLongitude = bounds.eastLongitude,
            ),
        )

        listOf(emptyList(), halves.take(1), halves).forEach { zones ->
            val plan = FogSnapshotVisualProbePlanner().plan(
                request(bounds),
                mapOf(key to opaqueMask()),
                exclusionZones = zones,
            )
            assertTrue(
                "zones=$zones left the unexplored tile unprovable AND unblocked",
                key in plan.probesByKey || key in plan.zoneBlockedKeys,
            )
        }
    }

    @Test
    fun exclusionZoneContainmentCrossesTheAntimeridian() {
        val zone = FogProbeExclusionZone(
            southLatitude = -10.0,
            northLatitude = 10.0,
            westLongitude = 170.0,
            eastLongitude = -170.0,
        )
        assertTrue(zone.contains(0.0, 175.0))
        assertTrue(zone.contains(0.0, -175.0))
        assertFalse(zone.contains(0.0, 0.0))
        assertFalse(zone.contains(20.0, 175.0))
    }

    private fun opaqueMask() = FogPixelMask(
        256,
        256,
        ByteArray(256 * 256) { 0xff.toByte() },
    )

    private fun request(bounds: FogTileBounds) = FogViewportCoverageRequest(
        center = GeoPoint(
            (bounds.northLatitude + bounds.southLatitude) / 2.0,
            (bounds.westLongitude + bounds.eastLongitude) / 2.0,
        ),
        floorZoom = 2,
        nearLeft = GeoPoint(bounds.southLatitude, bounds.westLongitude),
        farLeft = GeoPoint(bounds.northLatitude, bounds.westLongitude),
        farRight = GeoPoint(bounds.northLatitude, bounds.eastLongitude),
        nearRight = GeoPoint(bounds.southLatitude, bounds.eastLongitude),
    )

    private companion object {
        /** Probe centres are whole pixels; this only absorbs the projection round trip. */
        const val TOLERANCE_PIXELS = 1e-3
    }
}
