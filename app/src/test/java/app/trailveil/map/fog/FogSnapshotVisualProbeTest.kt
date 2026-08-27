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
}
