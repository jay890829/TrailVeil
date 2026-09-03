package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogScreenExclusionTest {
    @Test
    fun strongProbeSamplingRadiusInflatesOverlayFootprint() {
        val inflated = FogScreenRect(10.0, 20.0, 30.0, 40.0).inflate(1.0)

        assertEquals(FogScreenRect(9.0, 19.0, 31.0, 41.0), inflated)
    }

    @Test
    fun projectedRectanglePreservesASmallDatelineZone() {
        val zone = requireNotNull(
            fogProbeExclusionZoneForScreenRect(
                rectangle = FogScreenRect(40.0, 40.0, 60.0, 60.0),
                viewportWidth = 100,
                viewportHeight = 100,
            ) { x, _ ->
                GeoPoint(
                    latitude = 0.0,
                    longitude = if (x < 50.0) 179.0 else -179.0,
                )
            },
        )

        assertTrue(zone.contains(0.0, 179.5))
        assertTrue(zone.contains(0.0, -179.5))
        assertFalse(zone.contains(0.0, 0.0))
    }

    @Test
    fun wideWorldCopyFootprintFailsClosedInsteadOfChoosingTheWrongLongitudeArc() {
        val zone = requireNotNull(
            fogProbeExclusionZoneForScreenRect(
                rectangle = FogScreenRect(0.0, 0.0, 51.0, 10.0),
                viewportWidth = 100,
                viewportHeight = 100,
            ) { x, _ ->
                GeoPoint(latitude = 0.0, longitude = if (x < 25.0) -170.0 else 170.0)
            },
        )

        assertTrue("ambiguous world-copy span must be conservative", zone.contains(0.0, 0.0))
    }

    @Test
    fun projectionAtViewportBoundaryIsClippedBeforeConversion() {
        val projected = mutableListOf<Pair<Double, Double>>()
        val zone = requireNotNull(
            fogProbeExclusionZoneForScreenRect(
                rectangle = FogScreenRect(-20.0, -10.0, 20.0, 20.0).inflate(1.0),
                viewportWidth = 100,
                viewportHeight = 100,
            ) { x, y ->
                projected += x to y
                GeoPoint(latitude = y - 50.0, longitude = x - 50.0)
            },
        )

        assertTrue(projected.all { (x, y) -> x in 0.0..100.0 && y in 0.0..100.0 })
        assertTrue(zone.contains(-49.0, -49.0))
    }

    @Test
    fun fractionalEdgesQuantizeOutwardBeforeProjection() {
        val projected = mutableListOf<Pair<Double, Double>>()
        fogProbeExclusionZoneForScreenRect(
            rectangle = FogScreenRect(10.2, 20.2, 30.2, 40.2).inflate(1.0),
            viewportWidth = 100,
            viewportHeight = 100,
        ) { x, y ->
            projected += x to y
            GeoPoint(latitude = y, longitude = x)
        }

        assertEquals(
            listOf(
                9.0 to 19.0,
                9.0 to 42.0,
                32.0 to 19.0,
                32.0 to 42.0,
            ),
            projected,
        )
    }

    @Test
    fun projectionFailureReturnsWholeWorldSoCallerMustHideOverlays() {
        val zone = requireNotNull(
            fogProbeExclusionZoneForScreenRect(
                rectangle = FogScreenRect(10.0, 10.0, 20.0, 20.0),
                viewportWidth = 100,
                viewportHeight = 100,
            ) { _, _ -> null },
        )

        assertTrue(zone.contains(0.0, 0.0))
        assertTrue(zone.contains(80.0, 170.0))
    }

    @Test
    fun trackCrossingTheAntimeridianUsesTwoShortPaths() {
        val paths = splitTrackAtAntimeridian(
            listOf(
                GeoPoint(10.0, 179.0),
                GeoPoint(11.0, -179.0),
            ),
        )

        assertEquals(2, paths.size)
        assertEquals(180.0, paths[0].last().longitude, 0.0)
        assertEquals(-180.0, paths[1].first().longitude, 0.0)
        assertTrue(paths.all { path -> path.size >= 2 })
    }

    @Test
    fun zoneBlockedPlanRequestsHideAndReplanInsteadOfPassingVacuously() {
        val key = FogTileKey(2, 1, 1, FogRenderVersions.CURRENT)
        val bounds = FogPocTileGrid.bounds(key)
        val plan = FogSnapshotVisualProbePlanner().plan(
            request = viewport(bounds),
            masks = mapOf(
                key to FogPixelMask(256, 256, ByteArray(256 * 256) { 0xff.toByte() }),
            ),
            exclusionZones = listOf(wholeWorldFogProbeExclusionZone()),
        )
        var hidden = false
        val gate = FogOverlayVisibilityGate()
        assertTrue(gate.revealForProvenGeneration(1L))

        val preparation = prepareFogProofPlan(plan) {
            hidden = true
            gate.hide()
            true
        }

        assertFalse(preparation.canProve)
        assertTrue(preparation.overlaysHidden)
        assertTrue(hidden)
        assertFalse(plan.isProvable())
        assertEquals(null, gate.visibleGeneration)
    }

    @Test
    fun staleOverlayProofCannotRevealAnOlderGeneration() {
        val gate = FogOverlayVisibilityGate()

        assertTrue(gate.revealForProvenGeneration(2L))
        gate.hide()
        assertFalse(gate.revealForProvenGeneration(1L))
        assertTrue(gate.revealForProvenGeneration(2L))
        gate.showWithoutFogProof()
        assertEquals(Long.MIN_VALUE, gate.visibleGeneration)
    }

    private fun viewport(bounds: FogTileBounds) = FogViewportCoverageRequest(
        center = GeoPoint(
            latitude = (bounds.northLatitude + bounds.southLatitude) / 2.0,
            longitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
        ),
        floorZoom = 2,
        nearLeft = GeoPoint(bounds.southLatitude, bounds.westLongitude),
        farLeft = GeoPoint(bounds.northLatitude, bounds.westLongitude),
        farRight = GeoPoint(bounds.northLatitude, bounds.eastLongitude),
        nearRight = GeoPoint(bounds.southLatitude, bounds.eastLongitude),
    )
}
