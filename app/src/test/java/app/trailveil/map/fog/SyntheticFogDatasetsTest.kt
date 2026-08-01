package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticFogDatasetsTest {
    @Test
    fun typicalDatasetHasExactSizeAndStableFingerprint() {
        val dataset = SyntheticFogDatasets.typical10k()

        assertEquals(100, dataset.size)
        assertEquals(SyntheticFogDatasets.TYPICAL_POINT_COUNT, dataset.sumOf { it.points.size })
        assertNotEquals(0L, fingerprint(dataset))
        assertEquals(8840225033774219951L, fingerprint(dataset))
    }

    @Test
    fun stressDatasetHasExactSizeAndStableFingerprint() {
        val dataset = SyntheticFogDatasets.stress100k()

        assertEquals(SyntheticFogDatasets.STRESS_SEGMENT_COUNT, dataset.size)
        assertEquals(SyntheticFogDatasets.STRESS_POINT_COUNT, dataset.sumOf { it.points.size })
        assertNotEquals(0L, fingerprint(dataset))
        assertEquals(-1312930398936832955L, fingerprint(dataset))
    }

    @Test
    fun generatorIsRepeatableAndCreatesExplicitSegmentGaps() {
        val first = SyntheticFogDatasets.generate(12, 3, seed = 42)
        val second = SyntheticFogDatasets.generate(12, 3, seed = 42)

        assertEquals(first, second)
        assertEquals(listOf(0, 1, 2), first.map(TrackSegment::id))
        assertNotEquals(first[0].points.last(), first[1].points.first())
    }

    @Test
    fun stressDatasetRendersARepresentativeTileDeterministically() {
        val dataset = SyntheticFogDatasets.stress100k()
        val firstPoint = dataset.first().points.first()
        val tile = WebMercator.tile(firstPoint, zoom = 8)
        val key = FogTileKey(zoom = 8, x = tile.x, y = tile.y, renderVersion = 1)
        val renderer = FogTileRenderer(
            FogRenderStyle(tileSize = 64, revealRadiusMeters = 5_000.0),
        )

        val first = renderer.render(key, dataset)
        val second = renderer.render(key, dataset)

        assertEquals(first, second)
        assertTrue(first.copyAlpha().any { alpha -> alpha.toInt() == 0 })
    }

    @Test
    fun edgeCaseFixtureGuaranteesNonRandomGeometryCases() {
        val edgeCases = SyntheticFogDatasets.edgeCases()

        assertEquals((0..7).toList(), edgeCases.map(TrackSegment::id))
        assertEquals(listOf(179.75, -179.75), edgeCases[0].points.map(GeoPoint::longitude))
        assertEquals(WebMercator.MAX_LATITUDE, edgeCases[2].points.single().latitude, 0.0)
        assertEquals(-WebMercator.MAX_LATITUDE, edgeCases[3].points.single().latitude, 0.0)
        assertNotEquals(edgeCases[4].points.single(), edgeCases[5].points.single())
        assertEquals(edgeCases[6].points[0], edgeCases[6].points[1])
        assertEquals(180.0, edgeCases[7].points[1].longitude, 0.0)
    }

    private fun fingerprint(segments: List<TrackSegment>): Long {
        var hash = -3750763034362895579L
        segments.forEach { segment ->
            hash = mix(hash, segment.id.toLong())
            segment.points.forEach { point ->
                hash = mix(hash, point.latitude.toBits())
                hash = mix(hash, point.longitude.toBits())
            }
        }
        return hash
    }

    private fun mix(current: Long, value: Long): Long {
        var hash = current
        repeat(Long.SIZE_BYTES) { byteIndex ->
            hash = hash xor ((value ushr (byteIndex * Byte.SIZE_BITS)) and 0xff)
            hash *= 1099511628211L
        }
        return hash
    }
}
