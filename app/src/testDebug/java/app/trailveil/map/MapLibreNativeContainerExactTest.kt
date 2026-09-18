package app.trailveil.map

import app.trailveil.map.fog.*
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

class MapLibreNativeContainerExactTest {
    @Test fun allRingCoordinatesMetadataAndDetachedOwnershipMatchPriorSdkPath() = runBlocking {
        var cases = 0
        for (longitude in listOf(-539.99, -179.99, -0.0, 179.99, 539.99)) {
            for (latitude in listOf(-80.0, -0.0, 25.0330123456789, 80.0)) {
                for (holeCount in listOf(0, 1, 8)) for (reverse in listOf(false, true)) {
                    val shell = ring(latitude, longitude, .1).let { if (reverse) it.reversed() else it }.toMutableList()
                    val holes = List(holeCount) { index -> ring(latitude + index * .002, longitude + index * .002, .001).toMutableList() }
                    val geometry = geometry(listOf(FogNativePolygon(shell, holes), FogNativePolygon(shell.reversed())))
                    val expected = legacy(geometry)
                    val actual = actual(geometry)
                    assertCollections(expected, actual)
                    // Converted Point lists must not alias the input lists after preparation.
                    shell.clear(); holes.forEach { it.clear() }
                    assertCollections(expected, actual)
                    cases++
                }
            }
        }
        assertEquals(120, cases)
        assertCollections(legacy(geometry(emptyList())), actual(geometry(emptyList())))
    }

    @Test fun invalidRingsPreserveSdkFailureAndFullConversionOrder() = runBlocking {
        val valid = ring(0.0, 0.0, 1.0)
        val invalids = listOf(emptyList(), valid.take(1), valid.take(2), valid.take(3), valid.dropLast(1),
            valid.dropLast(1) + GeoPoint(0.25, 0.25),
            listOf(GeoPoint(-0.0, -0.0), GeoPoint(1.0, 0.0), GeoPoint(0.0, 1.0), GeoPoint(0.0, 0.0)))
        for (invalid in invalids) for (location in 0..4) {
            val outcomes = mutableListOf<Pair<Throwable, List<String>>>()
            for (reference in listOf(true, false)) {
                val reads = mutableListOf<String>()
                fun observed(label: String, values: List<GeoPoint>) = object : AbstractList<GeoPoint>() {
                    override val size get() = values.size
                    override fun get(index: Int): GeoPoint { reads += "$label:$index"; return values[index] }
                }
                val shell = observed("shell", if (location == 0 || location == 3) invalid else valid)
                val holes = listOf(observed("hole0", if (location == 1 || location == 4) invalid else if (location == 3) valid.take(2) else valid),
                    observed("hole1", if (location == 2) invalid else if (location == 4) valid.take(2) else valid), observed("last", valid))
                val input = geometry(listOf(FogNativePolygon(shell, holes)))
                val failure = runCatching { if (reference) legacy(input) else actual(input) }.exceptionOrNull()
                assertNotNull("SDK must refuse fixture", failure)
                assertEquals("last:${valid.lastIndex}", reads.last())
                outcomes += checkNotNull(failure) to reads.toList()
            }
            assertEquals(outcomes[0].first::class.java, outcomes[1].first::class.java)
            assertEquals(outcomes[0].first.message, outcomes[1].first.message)
            assertEquals(outcomes[0].second, outcomes[1].second)
        }
    }

    @Test fun cancellationAndSourceFailureKeepExactReadOrderAndRecovery() = runBlocking {
        for (cancel in listOf(false, true)) for (trigger in listOf(0, 255, 256, 257, 511)) {
            val outcomes = mutableListOf<Pair<Throwable, List<Int>>>()
            for (reference in listOf(true, false)) {
                val reads = mutableListOf<Int>()
                val job = Job()
                val sentinel = IllegalStateException("source failure at $trigger")
                val guarded = object : AbstractList<GeoPoint>() {
                    override val size = 1025
                    override fun get(index: Int): GeoPoint {
                        reads += index
                        if (index == trigger) { if (cancel) job.cancel() else throw sentinel }
                        return GeoPoint(0.0, 0.0)
                    }
                }
                val input = geometry(listOf(FogNativePolygon(guarded, listOf(ring(0.0, 0.0, .1)))))
                val failure = if (cancel) {
                    runCatching { withContext(job) { if (reference) legacy(input) else actual(input) } }.exceptionOrNull()
                } else {
                    // Capture the source exception at the method boundary, before withContext
                    // can recover/copy its stack trace when rethrowing across a coroutine boundary.
                    withContext(job) { runCatching { if (reference) legacy(input) else actual(input) }.exceptionOrNull() }
                }
                job.cancel()
                assertNotNull(failure)
                if (cancel) assertTrue(failure is CancellationException) else assertSame(sentinel, failure)
                outcomes += checkNotNull(failure) to reads.toList()
                assertCollections(legacy(geometry(listOf(FogNativePolygon(ring(0.0, 0.0, 1.0))))),
                    actual(geometry(listOf(FogNativePolygon(ring(0.0, 0.0, 1.0))))))
            }
            assertEquals(outcomes[0].first::class.java, outcomes[1].first::class.java)
            assertEquals(outcomes[0].second, outcomes[1].second)
        }
    }

    private fun assertCollections(expected: FeatureCollection, actual: FeatureCollection) {
        assertEquals(expected, actual)
        assertEquals(expected.type(), actual.type())
        assertEquals(expected.bbox(), actual.bbox())
        val oldFeatures = checkNotNull(expected.features()); val newFeatures = checkNotNull(actual.features())
        assertEquals(oldFeatures.size, newFeatures.size)
        oldFeatures.zip(newFeatures).forEach { (old, new) ->
            assertEquals(old.id(), new.id()); assertEquals(old.properties(), new.properties()); assertEquals(old.bbox(), new.bbox())
            val oldRings = (old.geometry() as Polygon).coordinates(); val newRings = (new.geometry() as Polygon).coordinates()
            assertEquals(oldRings.size, newRings.size)
            oldRings.zip(newRings).forEach { (a, b) ->
                assertEquals(a.size, b.size)
                a.zip(b).forEach { (x, y) ->
                    assertEquals(x.longitude().toRawBits(), y.longitude().toRawBits())
                    assertEquals(x.latitude().toRawBits(), y.latitude().toRawBits())
                    assertEquals(x.coordinates().size, y.coordinates().size)
                    assertEquals(x.bbox(), y.bbox())
                }
            }
        }
    }

    private suspend fun actual(geometry: FogNativeGeometry): FeatureCollection {
        val prepared: Any = suspendCoroutineUninterceptedOrReturn { continuation ->
            try { prepareMethod.invoke(null, geometry, continuation) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        assertSame(geometry, prepared.javaClass.getMethod("getGeometry").invoke(prepared))
        return prepared.javaClass.methods.single { it.name.startsWith("getFeatures") && it.parameterCount == 0 }.invoke(prepared) as FeatureCollection
    }

    // Frozen b57 conversion including its cancellation boundaries and deferred SDK ring validation.
    private suspend fun legacy(geometry: FogNativeGeometry): FeatureCollection {
        val context = currentCoroutineContext()
        context.ensureActive()
        val features = geometry.polygons.map { polygon ->
            context.ensureActive()
            fun nativeRing(points: List<GeoPoint>): LineString {
                val converted = ArrayList<Point>(points.size)
                points.forEachIndexed { index, point ->
                    if (index % 256 == 0) context.ensureActive()
                    converted += Point.fromLngLat(point.longitude, point.latitude)
                }
                return LineString.fromLngLats(converted)
            }
            Feature.fromGeometry(Polygon.fromOuterInner(nativeRing(polygon.shell), polygon.holes.map(::nativeRing)))
        }
        context.ensureActive()
        return FeatureCollection.fromFeatures(features)
    }
    private fun ring(latitude: Double, longitude: Double, size: Double) = listOf(
        GeoPoint(latitude, longitude), GeoPoint(latitude, longitude + size),
        GeoPoint(latitude + size, longitude + size), GeoPoint(latitude + size, longitude), GeoPoint(latitude, longitude))
    private fun geometry(polygons: List<FogNativePolygon>) = FogNativeGeometry(FogTileBounds(-180.0, -85.0, 180.0, 85.0), polygons, "synthetic")
    private companion object {
        val prepareMethod = Class.forName("app.trailveil.map.MapLibreVectorFogKt").methods.single { it.name == "prepareNativeFog" && it.parameterCount == 2 }
    }
}


