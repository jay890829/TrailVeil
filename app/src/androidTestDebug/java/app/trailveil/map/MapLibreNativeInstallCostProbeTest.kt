package app.trailveil.map

import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.map.fog.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Opt-in synthetic payload/real SDK submission costs; not canonical or end-to-end cover latency. */
class MapLibreNativeInstallCostProbeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun nativePayloadAndSdkSubmissionCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("trailveilMapLibreInstallCost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("trailveilExpectedApkHash"))
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        val preparedMode = args.getString("trailveilNativePrepared") == "true"
        val ringOrder = (args.getString("trailveilRingOrder") ?: "64,640,3375").split(',').map(String::toInt)
        check(ringOrder.sorted() == listOf(64, 640, 3375))
        val viewRef = AtomicReference<MapView>()
        composeRule.setContent {
            TrailVeilMapSurface(modifier = Modifier.fillMaxSize(),
                provider = MapProviderConfiguration("native-cost", "https://tiles.invalid/native-cost"),
                fallbackTimeoutMillis = 100L, onMapViewCreatedForTesting = viewRef::set)
        }
        composeRule.waitUntil(15_000L) { viewRef.get() != null }
        val view = checkNotNull(viewRef.get())
        val ready = CountDownLatch(1)
        val mapRef = AtomicReference<MapLibreMap>()
        instrumentation.runOnMainSync { view.getMapAsync { mapRef.set(it); ready.countDown() } }
        check(ready.await(15, TimeUnit.SECONDS))
        val map = checkNotNull(mapRef.get())
        composeRule.waitUntil(20_000L) { composeRule.runOnIdle { map.style?.isFullyLoaded == true } }
        val style = composeRule.runOnIdle { checkNotNull(map.style) }
        instrumentation.runOnMainSync {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 12.0))
            style.addSource(GeoJsonSource("q-empty", FeatureCollection.fromFeatures(emptyList())))
            style.addLayer(LineLayer("q-anchor", "q-empty"))
        }
        for (ringPoints in ringOrder) {
            val geometry = fixture(ringPoints)
            val vertexCount = geometry.polygons.sumOf { it.shell.size + it.holes.sumOf(List<GeoPoint>::size) }
            val conversion = JSONArray(); val source = JSONArray(); val mutation = JSONArray(); val progress = JSONArray()
            val mainWork = JSONArray(); val totalWork = JSONArray()
            val cleanup = JSONArray()
            var coordinateHash: String? = null
            repeat(7) { sample ->
                val sourceId = "q-$ringPoints-$sample-source"
                val layerId = "q-$ringPoints-$sample-layer"
                val start = AtomicLong()
                val completed = AtomicLong()
                val collectionRef = AtomicReference<FeatureCollection>()
                val workStarted = System.nanoTime()
                if (preparedMode) {
                    val preparationStarted = System.nanoTime()
                    collectionRef.set(runBlocking(Dispatchers.Default) {
                        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                        val prepared: Any = suspendCoroutineUninterceptedOrReturn { continuation ->
                            val method = Class.forName("app.trailveil.map.MapLibreVectorFogKt").methods.single {
                                it.name == "prepareNativeFog" && it.parameterCount == 2
                            }
                            try { method.invoke(null, geometry, continuation) }
                            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                        }
                        check(prepared.javaClass.getMethod("getGeometry").invoke(prepared) === geometry)
                        prepared.javaClass.methods.single { it.name.startsWith("getFeatures") && it.parameterCount == 0 }
                            .invoke(prepared) as FeatureCollection
                    })
                    conversion.put(System.nanoTime() - preparationStarted)
                }
                val frame = CountDownLatch(1)
                val armed = AtomicBoolean(false)
                lateinit var listener: MapView.OnDidFinishRenderingFrameListener
                listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                    if (armed.compareAndSet(true, false)) {
                        progress.put(System.nanoTime() - completed.get())
                        view.removeOnDidFinishRenderingFrameListener(listener)
                        frame.countDown()
                    }
                }
                instrumentation.runOnMainSync {
                    view.addOnDidFinishRenderingFrameListener(listener)
                    start.set(System.nanoTime())
                    val collection = if (preparedMode) checkNotNull(collectionRef.get()) else legacyFeatures(geometry)
                    if (!preparedMode) conversion.put(System.nanoTime() - start.get())
                    collectionRef.set(collection)
                    val sourceStart = System.nanoTime()
                    val geoSource = GeoJsonSource(sourceId, collection)
                    source.put(System.nanoTime() - sourceStart)
                    val mutationStart = System.nanoTime()
                    style.addSource(geoSource)
                    style.addLayerBelow(FillLayer(layerId, sourceId).withProperties(
                        PropertyFactory.fillColor(MAPLIBRE_FOG_COLOR), PropertyFactory.fillOpacity(184f / 255f),
                        PropertyFactory.fillAntialias(true)), "q-anchor")
                    completed.set(System.nanoTime())
                    mutation.put(completed.get() - mutationStart)
                    mainWork.put(completed.get() - start.get())
                    totalWork.put(completed.get() - workStarted)
                    check(view.post { armed.set(true); map.triggerRepaint() })
                }
                check(frame.await(10, TimeUnit.SECONDS)) { "renderer progress timeout" }
                // Read only the detached data object, after timing/renderer observation, off Main.
                val identity = featureHash(checkNotNull(collectionRef.get()))
                check(coordinateHash == null || coordinateHash == identity)
                coordinateHash = identity
                val cleaned = CountDownLatch(1)
                val cleanupArmed = AtomicBoolean(false)
                val cleanupStart = System.nanoTime()
                lateinit var cleanupListener: MapView.OnDidFinishRenderingFrameListener
                cleanupListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                    if (cleanupArmed.compareAndSet(true, false)) {
                        view.removeOnDidFinishRenderingFrameListener(cleanupListener)
                        cleaned.countDown()
                    }
                }
                instrumentation.runOnMainSync {
                    check(style.getSource(sourceId) is GeoJsonSource && style.getLayer(layerId) is FillLayer)
                    view.addOnDidFinishRenderingFrameListener(cleanupListener)
                    style.removeLayer(layerId); style.removeSource(sourceId)
                    check(view.post { cleanupArmed.set(true); map.triggerRepaint() })
                }
                check(cleaned.await(10, TimeUnit.SECONDS)) { "cleanup renderer progress timeout" }
                cleanup.put(System.nanoTime() - cleanupStart)
                instrumentation.runOnMainSync { check(style.getLayer(layerId) == null && style.getSource(sourceId) == null) }
            }
            val record = JSONObject().put("case", "vertices$vertexCount").put("vertices", vertexCount)
                .put("appSha256", appHash).put("testSha256", testHash).put("fingerprint", Build.FINGERPRINT)
                .put("coordinateSha256", coordinateHash).put("conversionNs", conversion).put("sourceConstructorNs", source)
                .put("styleMutationNs", mutation).put("nextRendererProgressNs", progress)
                .put("prepared", preparedMode).put("mainWorkNs", mainWork).put("prepareAndSubmitNs", totalWork)
                .put("cleanupProgressNs", cleanup).put("ringOrder", ringOrder.joinToString(","))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "MAPLIBRE_NATIVE_COST $record\n") })
        }
    }

    // Frozen b45 installNativeFog conversion, with all data/ordering preserved.
    private fun legacyFeatures(geometry: FogNativeGeometry): FeatureCollection {
        val features = geometry.polygons.map { polygon ->
            fun nativeRing(points: List<GeoPoint>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
            Feature.fromGeometry(Polygon.fromOuterInner(nativeRing(polygon.shell), polygon.holes.map(::nativeRing)))
        }
        return FeatureCollection.fromFeatures(features)
    }
    private fun fixture(points: Int): FogNativeGeometry {
        val shell = listOf(GeoPoint(-0.04, -0.04), GeoPoint(-0.04, 0.04), GeoPoint(0.04, 0.04), GeoPoint(0.04, -0.04), GeoPoint(-0.04, -0.04))
        val holes = (0 until 8).map { index ->
            val x = -0.027 + (index % 4) * 0.018
            val y = if (index < 4) -0.014 else 0.014
            val ring = (0 until points).map { i ->
                val theta = 2.0 * PI * i / points
                GeoPoint(y - sin(theta) * 0.005, x + cos(theta) * 0.005)
            }
            ring + ring.first()
        }
        return FogNativeGeometry(FogTileBounds(-0.04, -0.04, 0.04, 0.04), listOf(FogNativePolygon(shell, holes)), "synthetic")
    }
    private fun featureHash(collection: FeatureCollection): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = java.nio.ByteBuffer.allocate(16)
        fun size(value: Int) { digest.update(java.nio.ByteBuffer.allocate(4).putInt(value).array()) }
        size(collection.features()!!.size)
        collection.features()!!.forEach { feature ->
            val rings = (feature.geometry() as Polygon).coordinates()
            size(rings.size)
            rings.forEach { ring ->
                size(ring.size)
                ring.forEach { point ->
                    bytes.clear(); bytes.putDouble(point.longitude()).putDouble(point.latitude()); digest.update(bytes.array())
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
