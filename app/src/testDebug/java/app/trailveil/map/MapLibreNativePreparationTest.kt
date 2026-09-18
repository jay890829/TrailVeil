package app.trailveil.map

import app.trailveil.map.fog.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

class MapLibreNativePreparationTest {
    @Test fun preparedCoordinatesAndStructureMatchB45WithoutJsonRounding() = runTest {
        val shell = listOf(GeoPoint(25.0330123456789, 121.5640123456789), GeoPoint(25.034, 121.565),
            GeoPoint(25.033, 121.566), GeoPoint(25.0330123456789, 121.5640123456789))
        val hole = listOf(GeoPoint(25.0332, 121.5645), GeoPoint(25.0333, 121.5646),
            GeoPoint(25.0332, 121.5647), GeoPoint(25.0332, 121.5645))
        for (polygons in listOf(emptyList(), listOf(FogNativePolygon(shell)),
            listOf(FogNativePolygon(shell, listOf(hole)), FogNativePolygon(shell.reversed())))) {
            val geometry = FogNativeGeometry(FogTileBounds(121.5, 25.0, 121.6, 25.1), polygons, "fixture")
            val prepared = prepareNativeFog(geometry)
            assertSame(geometry, prepared.geometry)
            assertEquals(reference(geometry), prepared.features)
            if (polygons.isNotEmpty()) {
                val point = (prepared.features.features()!!.first().geometry() as Polygon).coordinates()[0][0]
                assertEquals(shell.first().longitude.toBits(), point.longitude().toBits())
                assertEquals(shell.first().latitude.toBits(), point.latitude().toBits())
            }
        }
    }

    @Test fun datelineAndSignedZeroCoordinatesStayBitExact() = runTest {
        val shell = listOf(GeoPoint(-0.0, 179.999999999123), GeoPoint(0.01, 180.01),
            GeoPoint(-0.01, 180.01), GeoPoint(-0.0, 179.999999999123))
        val geometry = FogNativeGeometry(FogTileBounds(179.9, -0.1, 180.1, 0.1), listOf(FogNativePolygon(shell)), "fixture")
        val prepared = prepareNativeFog(geometry)
        assertEquals(reference(geometry), prepared.features)
        val points = (prepared.features.features()!!.single().geometry() as Polygon).coordinates().single()
        shell.zip(points).forEach { (expected, actual) ->
            assertEquals(expected.latitude.toBits(), actual.latitude().toBits())
            assertEquals(expected.longitude.toBits(), actual.longitude().toBits())
        }
    }

    @Test fun cancellationInsideALargeRingStopsBeforePreparingTheWholePayload() = runTest {
        val job = Job()
        var reads = 0
        val points = object : AbstractList<GeoPoint>() {
            override val size: Int = 1025
            override fun get(index: Int): GeoPoint {
                reads++
                if (index == 257) job.cancel()
                return GeoPoint(0.0, 0.0)
            }
        }
        val geometry = FogNativeGeometry(FogTileBounds(-1.0, -1.0, 1.0, 1.0), listOf(FogNativePolygon(points)), "cancel")
        try {
            withContext(job) { prepareNativeFog(geometry) }
            fail("cancelled preparation returned")
        } catch (_: CancellationException) { /* expected */ }
        assertTrue("cancel must interrupt within one 256-point chunk; actual reads=$reads", reads in 258..514)
    }

    // Frozen conversion from b45; only the resulting FeatureCollection is returned for comparison.
    private fun reference(geometry: FogNativeGeometry): FeatureCollection {
        val features = geometry.polygons.map { polygon ->
            fun nativeRing(points: List<GeoPoint>) = LineString.fromLngLats(
                points.map { Point.fromLngLat(it.longitude, it.latitude) })
            Feature.fromGeometry(Polygon.fromOuterInner(nativeRing(polygon.shell), polygon.holes.map(::nativeRing)))
        }
        return FeatureCollection.fromFeatures(features)
    }
}
