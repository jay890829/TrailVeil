package app.trailveil.map

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.map.fog.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Same test APK calls the actual preparation in b57 and the candidate, without SDK submission. */
class MapLibreNativeContainerCostTest {
    @Test fun actualNativeContainerWorkerCosts() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("w4Cost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("w4ExpectedApkHash"))
        val order = (args.getString("w4CaseOrder") ?: "small,holes64,vertices27000,holes5400").split(',')
        check(order.sorted() == listOf("holes5400", "holes64", "small", "vertices27000"))
        val prepare = Class.forName("app.trailveil.map.MapLibreVectorFogKt").methods.single { it.name == "prepareNativeFog" && it.parameterCount == 2 }
        val preparedType = Class.forName("app.trailveil.map.PreparedNativeFog")
        val geometryGetter = preparedType.getMethod("getGeometry")
        val featuresGetter = preparedType.methods.single { it.name.startsWith("getFeatures") && it.parameterCount == 0 }
        for (name in order) {
            val geometry = fixture(name)
            val inputHash = geometryHash(geometry)
            val expected = legacy(geometry)
            val outputHash = collectionHash(expected)
            val samples = JSONArray()
            repeat(7) { sample ->
                val result = runBlocking(Dispatchers.Default) {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    val cpu = Debug.threadCpuTimeNanos(); val start = System.nanoTime()
                    val prepared: Any = suspendCoroutineUninterceptedOrReturn { continuation ->
                        try { prepare.invoke(null, geometry, continuation) }
                        catch (failure: InvocationTargetException) { throw failure.targetException }
                    }
                    val wall = System.nanoTime() - start; val elapsedCpu = Debug.threadCpuTimeNanos() - cpu
                    Triple(prepared, wall, elapsedCpu)
                }
                assertSame(geometry, geometryGetter.invoke(result.first))
                val actual = featuresGetter.invoke(result.first) as FeatureCollection
                assertEquals(expected, actual)
                assertEquals(outputHash, collectionHash(actual))
                assertEquals(inputHash, geometryHash(geometry))
                samples.put(JSONObject().put("sample", sample).put("wallNs", result.second).put("cpuNs", result.third))
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "W4_CONTAINER " + JSONObject().put("case", name).put("appSha256", appHash)
                    .put("testSha256", testHash).put("inputSha256", inputHash).put("outputSha256", outputHash)
                    .put("polygons", geometry.polygons.size).put("rings", geometry.polygons.sumOf { 1 + it.holes.size })
                    .put("vertices", geometry.polygons.sumOf { it.shell.size + it.holes.sumOf(List<GeoPoint>::size) })
                    .put("samples", samples).toString() + "\n")
            })
        }
    }

    private fun fixture(name: String): FogNativeGeometry {
        val shell = square(-2.0, -2.0, 4.0)
        val polygon = when (name) {
            "vertices27000" -> {
                val points = List(27000) { index -> val angle = index * 2.0 * PI / 27000
                    GeoPoint(1.9 * sin(angle), 1.9 * cos(angle)) }
                FogNativePolygon(points + points.first())
            }
            "small" -> FogNativePolygon(shell)
            else -> {
                val count = if (name == "holes64") 64 else 5400
                FogNativePolygon(shell, List(count) { index ->
                    square(-1.8 + index / 75 * .048, -1.8 + index % 75 * .048, .02).reversed()
                })
            }
        }
        return FogNativeGeometry(FogTileBounds(-2.0, -2.0, 2.0, 2.0), listOf(polygon), "synthetic-container")
    }
    private fun square(latitude: Double, longitude: Double, side: Double) = listOf(
        GeoPoint(latitude, longitude), GeoPoint(latitude, longitude + side), GeoPoint(latitude + side, longitude + side),
        GeoPoint(latitude + side, longitude), GeoPoint(latitude, longitude))
    private fun legacy(geometry: FogNativeGeometry) = FeatureCollection.fromFeatures(geometry.polygons.map { polygon ->
        fun ring(points: List<GeoPoint>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
        Feature.fromGeometry(Polygon.fromOuterInner(ring(polygon.shell), polygon.holes.map(::ring)))
    })
    private fun geometryHash(geometry: FogNativeGeometry): String {
        val writer = Writer()
        writer.long(geometry.bounds.westLongitude.toRawBits()); writer.long(geometry.bounds.eastLongitude.toRawBits())
        writer.long(geometry.bounds.northLatitude.toRawBits()); writer.long(geometry.bounds.southLatitude.toRawBits())
        writer.int(geometry.polygons.size)
        geometry.polygons.forEach { polygon ->
            writer.int(1 + polygon.holes.size)
            (listOf(polygon.shell) + polygon.holes).forEach { ring -> writer.int(ring.size); ring.forEach { writer.long(it.longitude.toRawBits()); writer.long(it.latitude.toRawBits()) } }
        }
        return writer.finish()
    }
    private fun collectionHash(collection: FeatureCollection): String {
        val writer = Writer(); val features = checkNotNull(collection.features()); writer.int(features.size)
        features.forEach { feature ->
            val rings = (feature.geometry() as Polygon).coordinates(); writer.int(rings.size)
            rings.forEach { ring -> writer.int(ring.size); ring.forEach { writer.long(it.longitude().toRawBits()); writer.long(it.latitude().toRawBits()) } }
        }
        return writer.finish()
    }
    private class Writer {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val buffer = ByteBuffer.allocate(8192)
        fun int(value: Int) { room(4); buffer.putInt(value) }
        fun long(value: Long) { room(8); buffer.putLong(value) }
        private fun room(bytes: Int) { if (buffer.remaining() < bytes) flush() }
        private fun flush() { digest.update(buffer.array(), 0, buffer.position()); buffer.clear() }
        fun finish(): String { flush(); return digest.digest().joinToString("") { "%02x".format(it) } }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
