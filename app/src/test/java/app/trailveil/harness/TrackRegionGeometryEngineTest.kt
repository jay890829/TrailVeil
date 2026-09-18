package app.trailveil.harness

import app.trailveil.map.fog.FogNativeGeometry
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRevealUpdate
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.TrackSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

class TrackRegionGeometryEngineTest {
    private val style = FogRenderStyle()
    // Public synthetic coordinates near the equator; no device location or canonical fixture.
    private val bounds = FogTileBounds(0.01, 0.01, 0.02, 0.02)
    private val factory = GeometryFactory()

    @Test fun alternatingPartitionSetsReuseUnionsAndAnUnrelatedAdditionKeepsTheOther() = runBlocking {
        val engine = TrackRegionGeometryEngine()
        val other = FogTileBounds(0.10, 0.01, 0.12, 0.02)
        val tracks = listOf(segment(0, 0.014 to 0.015, 0.016 to 0.015))
        val first = checkNotNull(engine.render(bounds, style) { tracks })
        engine.render(other, style) { emptyList() }
        val again = checkNotNull(engine.render(bounds, style) { error("already cached") })
        assertTrue(again.diagnostics.contains("unionHit=1"))
        assertEquals(first.polygons, again.polygons)
        engine.invalidate(listOf(FogRevealUpdate(GeoPoint(0.015, 0.11))), style)
        val otherTracks = listOf(segment(1, 0.11 to 0.015))
        val changed = checkNotNull(engine.render(other, style) { otherTracks })
        assertTrue(changed.diagnostics.contains("unionHit=0"))
        assertFalse(fogged(changed, 0.11, 0.015))
        val unchanged = checkNotNull(engine.render(bounds, style) { error("unaffected") })
        assertTrue(unchanged.diagnostics.contains("unionHit=1"))
        assertEquals(first.polygons, unchanged.polygons)
        engine.clear()
        for (window in listOf(bounds, other)) {
            val cleared = checkNotNull(engine.render(window, style) { emptyList() })
            assertTrue(cleared.diagnostics.contains("unionHit=0"))
            val reference = checkNotNull(TrackRegionGeometryEngine().render(window, style) { emptyList() })
            assertEquals(reference.polygons, cleared.polygons)
        }
    }

    @Test fun unionReuseStillClipsToTheNewViewportAndClearDropsIt() = runBlocking {
        val engine = TrackRegionGeometryEngine()
        val tracks = listOf(segment(0, 0.014 to 0.015, 0.016 to 0.015))
        val first = checkNotNull(engine.render(bounds, style) { tracks })
        assertTrue(first.diagnostics.contains("unionHit=0"))
        val cropped = FogTileBounds(0.012, 0.012, 0.018, 0.018)
        val reused = checkNotNull(engine.render(cropped, style) { error("partition must be cached") })
        assertTrue(reused.diagnostics.contains("unionHit=1"))
        val reference = checkNotNull(TrackRegionGeometryEngine().render(cropped, style) { tracks })
        assertEquals(reference.polygons, reused.polygons)
        assertEquals(cropped, reused.bounds)
        engine.clear()
        val cleared = checkNotNull(engine.render(cropped, style) { emptyList() })
        assertTrue(cleared.diagnostics.contains("unionHit=0"))
        assertTrue(fogged(cleared, 0.015, 0.015))
    }

    @Test fun additionsAndRadiusChangesCannotReuseAStaleUnion() = runBlocking {
        val engine = TrackRegionGeometryEngine()
        engine.render(bounds, style) { emptyList() }
        engine.render(bounds, style) { error("cached") }
        val tracks = listOf(segment(0, 0.015 to 0.015))
        engine.invalidate(listOf(FogRevealUpdate(GeoPoint(0.015, 0.015))), style)
        val added = checkNotNull(engine.render(bounds, style) { tracks })
        assertTrue(added.diagnostics.contains("unionHit=0"))
        assertFalse(fogged(added, 0.015, 0.015))
        val larger = style.copy(revealRadiusMeters = 50.0)
        val resized = checkNotNull(engine.render(bounds, larger) { tracks })
        val reference = checkNotNull(TrackRegionGeometryEngine().render(bounds, larger) { tracks })
        assertTrue(resized.diagnostics.contains("unionHit=0"))
        assertEquals(reference.polygons, resized.polygons)
    }

    @Test fun emptyStorageLeavesTheWholeRectangleFogged() = runBlocking {
        val output = checkNotNull(TrackRegionGeometryEngine().render(bounds, style) { emptyList() })
        assertTrue(fogged(output, 0.015, 0.015))
        assertEquals(1, output.polygons.size)
    }

    @Test fun aTrackRevealsItsCorridorButNotNearbyGround() = runBlocking {
        val output = render(listOf(segment(0, 0.014 to 0.015, 0.016 to 0.015)))
        assertFalse(fogged(output, 0.015, 0.015))
        assertTrue(fogged(output, 0.015, 0.016))
        assertTrue(output.polygons.all { polygon -> polygon.shell.first() == polygon.shell.last() })
    }

    @Test fun separateSegmentsDoNotAcquireAConnectingCorridor() = runBlocking {
        val output = render(listOf(segment(0, 0.012 to 0.015), segment(1, 0.018 to 0.015)))
        assertFalse(fogged(output, 0.012, 0.015))
        assertFalse(fogged(output, 0.018, 0.015))
        assertTrue(fogged(output, 0.015, 0.015))
    }

    @Test fun overlappingWalksUnionWithoutRefillingTheirIntersection() = runBlocking {
        val output = render(listOf(
            segment(0, 0.012 to 0.015, 0.018 to 0.015),
            segment(1, 0.015 to 0.012, 0.015 to 0.018),
        ))
        assertFalse(fogged(output, 0.015, 0.015))
        assertTrue(fogged(output, 0.012, 0.012))
        validate(output)
    }

    @Test fun crossingTheViewportExteriorCreatesValidShellsInsteadOfTouchingHoles() = runBlocking {
        val output = render(listOf(segment(0, 0.005 to 0.015, 0.025 to 0.015)))
        assertFalse(fogged(output, 0.01001, 0.015))
        assertFalse(fogged(output, 0.01999, 0.015))
        assertEquals(2, output.polygons.size)
        validate(output)
    }

    @Test fun zoomingInsideCachedPartitionsPerformsNoMoreReads() = runBlocking {
        val engine = TrackRegionGeometryEngine()
        var reads = 0
        val tracks = listOf(segment(0, 0.014 to 0.015, 0.016 to 0.015))
        val first = checkNotNull(engine.render(bounds, style) { reads++; tracks })
        val firstReads = reads
        val second = checkNotNull(engine.render(FogTileBounds(0.012, 0.012, 0.018, 0.018), style) {
            reads++; tracks
        })
        assertEquals(firstReads, reads)
        assertTrue(engine.cacheHits > 0)
        assertFalse(fogged(first, 0.015, 0.015))
        assertFalse(fogged(second, 0.015, 0.015))
    }

    @Test fun additionsInvalidateAffectedPartitionsButNotDistantOnes() = runBlocking {
        val engine = TrackRegionGeometryEngine()
        var reads = 0
        engine.render(bounds, style) { reads++; emptyList() }
        engine.invalidate(listOf(FogRevealUpdate(GeoPoint(30.0, 30.0))), style)
        val before = reads
        engine.render(bounds, style) { reads++; emptyList() }
        assertEquals(before, reads)
        engine.invalidate(listOf(FogRevealUpdate(GeoPoint(0.015, 0.015))), style)
        val changed = checkNotNull(engine.render(bounds, style) {
            reads++; listOf(segment(0, 0.015 to 0.015))
        })
        assertTrue(reads > before)
        assertFalse(fogged(changed, 0.015, 0.015))
    }

    @Test fun deletionClearDoesNotReuseAFormerHole() = runBlocking {
        val engine = TrackRegionGeometryEngine()
        val first = checkNotNull(engine.render(bounds, style) { listOf(segment(0, 0.015 to 0.015)) })
        assertFalse(fogged(first, 0.015, 0.015))
        engine.clear()
        val second = checkNotNull(engine.render(bounds, style) { emptyList() })
        assertTrue(fogged(second, 0.015, 0.015))
    }

    @Test fun broadAndWrappedRequestsExplicitlyFallbackBeforeReading() = runBlocking {
        val engine = TrackRegionGeometryEngine()
        val read: suspend (app.trailveil.data.map.ViewportBounds) -> List<TrackSegment> = {
            error("fallback must not enumerate worldwide canonical points")
        }
        assertNull(engine.render(FogTileBounds(-10.0, -10.0, 10.0, 10.0), style, read))
        assertNull(engine.render(FogTileBounds(179.9, 0.0, 180.1, 0.1), style, read))
    }

    @Test fun cancellationIsNotConvertedToAValidFallback() {
        assertThrows(CancellationException::class.java) {
            runBlocking { TrackRegionGeometryEngine().render(bounds, style) { throw CancellationException() } }
        }
    }

    @Test fun aLongHighLatitudeEdgeDoesNotLoseTheHighEndOfItsRadius() = runBlocking {
        val south = 79.0
        val north = 79.054797
        val testBounds = FogTileBounds(10.04, south - 0.002, 10.06, north + 0.002)
        val output = checkNotNull(TrackRegionGeometryEngine().render(testBounds, style) {
            listOf(segment(0, 10.05 to south, 10.05 to north))
        })
        // At 80% along the edge the old one-buffer connector lost ~0.10 m of its 25 m radius.
        val latitude = south + (north - south) * 0.8
        val insideOffset = 24.95 / (111_319.49079327358 * kotlin.math.cos(Math.toRadians(latitude)))
        assertFalse("connector must respect its per-bin radius bound",
            fogged(output, 10.05 + insideOffset, latitude))
        val outsideOffset = 25.10 / (111_319.49079327358 * kotlin.math.cos(Math.toRadians(latitude)))
        assertTrue("connector must not enlarge the explored corridor",
            fogged(output, 10.05 + outsideOffset, latitude))
    }

    private suspend fun render(segments: List<TrackSegment>) =
        checkNotNull(TrackRegionGeometryEngine().render(bounds, style) { segments })

    private fun segment(id: Int, vararg coordinates: Pair<Double, Double>) =
        TrackSegment(id, coordinates.map { (lon, lat) -> GeoPoint(lat, lon) })

    private fun polygon(p: app.trailveil.map.fog.FogNativePolygon): org.locationtech.jts.geom.Polygon {
        fun ring(points: List<GeoPoint>) = factory.createLinearRing(points.map {
            Coordinate(it.longitude, it.latitude)
        }.toTypedArray())
        return factory.createPolygon(ring(p.shell), p.holes.map(::ring).toTypedArray())
    }

    private fun fogged(output: FogNativeGeometry, lon: Double, lat: Double): Boolean =
        output.polygons.any { polygon(it).covers(factory.createPoint(Coordinate(lon, lat))) }

    private fun validate(output: FogNativeGeometry) {
        output.polygons.forEach { assertTrue("native polygon must be valid", polygon(it).isValid) }
    }
}
