package app.trailveil.map

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.map.fog.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.FeatureCollection

/** Same APK on b48 and U2: candidate APIs are invoked only through reflection. */
class MapLibreRasterCostProbeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun packingBitmapAndSdkSubmissionCosts() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("u2Cost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appHash = digest(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        val testHash = digest(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("u2ExpectedApkHash"))
        val preparedMode = args.getString("u2Prepared") == "true"
        val sizes = (args.getString("u2SizeOrder") ?: "256,768,1280").split(',').map(String::toInt)
        check(sizes.sorted() == listOf(256, 768, 1280))
        val prepareMethod = if (preparedMode) Class.forName("app.trailveil.map.MapLibreRasterPreparationKt")
            .methods.single { it.name == "prepareRasterFog" && it.parameterCount == 2 } else null
        val bitmapMethod = if (preparedMode) Class.forName("app.trailveil.map.PreparedRasterFog")
            .getMethod("createBitmapFor", FogTileMosaic::class.java) else null
        val pixelsField = if (preparedMode) Class.forName("app.trailveil.map.PreparedRasterFog")
            .getDeclaredField("pixels").apply { isAccessible = true } else null
        val viewRef = AtomicReference<MapView>()
        composeRule.setContent {
            TrailVeilMapSurface(modifier = Modifier.fillMaxSize(),
                provider = MapProviderConfiguration("u2-cost", "https://tiles.invalid/u2-cost"),
                fallbackTimeoutMillis = 100L, onMapViewCreatedForTesting = viewRef::set)
        }
        composeRule.waitUntil(20_000L) { viewRef.get() != null }
        val view = viewRef.get()
        val mapRef = AtomicReference<MapLibreMap>()
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync { view.getMapAsync { mapRef.set(it); ready.countDown() } }
        check(ready.await(20, TimeUnit.SECONDS))
        val map = mapRef.get()
        composeRule.waitUntil(20_000L) { composeRule.runOnIdle { map.style?.isFullyLoaded == true } }
        val style = composeRule.runOnIdle { checkNotNull(map.style) }
        instrumentation.runOnMainSync {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 12.0))
            style.addSource(GeoJsonSource("u2-anchor-source", FeatureCollection.fromFeatures(emptyList())))
            style.addLayer(LineLayer("u2-anchor", "u2-anchor-source"))
        }
        val bounds = FogTileBounds(-0.03, -0.03, 0.03, 0.03)
        val quad = LatLngQuad(LatLng(0.03, -0.03), LatLng(0.03, 0.03), LatLng(-0.03, 0.03), LatLng(-0.03, -0.03))
        for (size in sizes) {
            val samples = JSONArray()
            var rawHash: String? = null
            var bitmapHash: String? = null
            repeat(7) { sample ->
                val alpha = ByteArray(size * size) { index -> (index * 73 + index / size * 17).toByte() }
                val mosaic = FogTileMosaic(FogPixelMask(size, size, alpha), bounds, (size / 256) * (size / 256))
                val sourceId = "u2-$size-$sample-source"
                val layerId = "u2-$size-$sample-layer"
                val preparedRef = AtomicReference<Any?>()
                val rawRef = AtomicReference<IntArray?>()
                val bitmapRef = AtomicReference<Bitmap?>()
                val packingCpu = AtomicLong()
                val packingWall = AtomicLong()
                var dispatchPacking = 0L
                val mainCpu = AtomicLong()
                val mainWall = AtomicLong()
                val bitmapWall = AtomicLong()
                val sourceWall = AtomicLong()
                val mutationWall = AtomicLong()
                val submittedAt = AtomicLong()
                val progressAt = AtomicLong()
                val before = counters()
                val workStarted = System.nanoTime()
                if (preparedMode) {
                    val dispatched = System.nanoTime()
                    preparedRef.set(runBlocking(Dispatchers.Default) {
                        check(Looper.myLooper() != Looper.getMainLooper())
                        val cpu = Debug.threadCpuTimeNanos()
                        val start = System.nanoTime()
                        val prepared: Any = suspendCoroutineUninterceptedOrReturn { continuation ->
                            try { checkNotNull(prepareMethod).invoke(null, mosaic, continuation) }
                            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                        }
                        packingWall.set(System.nanoTime() - start)
                        packingCpu.set(Debug.threadCpuTimeNanos() - cpu)
                        prepared
                    })
                    dispatchPacking = System.nanoTime() - dispatched
                }
                val progress = CountDownLatch(1)
                val armed = AtomicBoolean()
                lateinit var listener: MapView.OnDidFinishRenderingFrameListener
                listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                    if (armed.compareAndSet(true, false)) {
                        progressAt.set(System.nanoTime())
                        view.removeOnDidFinishRenderingFrameListener(listener)
                        progress.countDown()
                    }
                }
                instrumentation.runOnMainSync {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    view.addOnDidFinishRenderingFrameListener(listener)
                    val cpu = Debug.threadCpuTimeNanos()
                    val start = System.nanoTime()
                    if (!preparedMode) {
                        val packCpu = Debug.threadCpuTimeNanos()
                        val packStart = System.nanoTime()
                        rawRef.set(legacyPixels(mosaic.mask))
                        packingWall.set(System.nanoTime() - packStart)
                        packingCpu.set(Debug.threadCpuTimeNanos() - packCpu)
                    }
                    val bitmapStart = System.nanoTime()
                    val bitmap = if (preparedMode) {
                        try { checkNotNull(bitmapMethod).invoke(preparedRef.get(), mosaic) as Bitmap }
                        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                    } else Bitmap.createBitmap(checkNotNull(rawRef.get()), size, size, Bitmap.Config.ARGB_8888)
                    bitmapRef.set(bitmap)
                    bitmapWall.set(System.nanoTime() - bitmapStart)
                    val sourceStart = System.nanoTime()
                    val source = ImageSource(sourceId, quad, bitmap)
                    sourceWall.set(System.nanoTime() - sourceStart)
                    val mutationStart = System.nanoTime()
                    style.addSource(source)
                    style.addLayerBelow(RasterLayer(layerId, sourceId).withProperties(
                        PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
                        PropertyFactory.rasterOpacity(1f)), "u2-anchor")
                    submittedAt.set(System.nanoTime())
                    mutationWall.set(submittedAt.get() - mutationStart)
                    mainWall.set(submittedAt.get() - start)
                    mainCpu.set(Debug.threadCpuTimeNanos() - cpu)
                    check(view.post { armed.set(true); map.triggerRepaint() })
                }
                val afterSubmit = counters()
                check(progress.await(15, TimeUnit.SECONDS)) { "SDK progress timeout" }
                // All oracle allocation is outside the reported work/counter interval.
                val actualRaw = if (preparedMode) checkNotNull(pixelsField).get(preparedRef.get()) as IntArray else checkNotNull(rawRef.get())
                val expectedRaw = legacyPixels(mosaic.mask)
                assertArrayEquals(expectedRaw, actualRaw)
                val actualBitmapPixels = IntArray(size * size)
                val referenceBitmapPixels = IntArray(size * size)
                instrumentation.runOnMainSync {
                    checkNotNull(bitmapRef.get()).getPixels(actualBitmapPixels, 0, size, 0, 0, size, size)
                    val referenceBitmap = Bitmap.createBitmap(expectedRaw, size, size, Bitmap.Config.ARGB_8888)
                    referenceBitmap.getPixels(referenceBitmapPixels, 0, size, 0, 0, size, size)
                    assertTrue(style.getSource(sourceId) is ImageSource && style.getLayer(layerId) is RasterLayer)
                }
                assertArrayEquals("Compare two Bitmaps after identical premultiplication", referenceBitmapPixels, actualBitmapPixels)
                val rawIdentity = pixelsHash(actualRaw)
                val decodedHash = pixelsHash(actualBitmapPixels)
                check(rawHash == null || rawHash == rawIdentity)
                check(bitmapHash == null || bitmapHash == decodedHash)
                rawHash = rawIdentity; bitmapHash = decodedHash
                samples.put(JSONObject().put("sample", sample).put("packingCpuNs", packingCpu.get())
                    .put("packingWallNs", packingWall.get()).put("dispatchAndPackingNs", if (preparedMode) dispatchPacking else packingWall.get())
                    .put("mainCpuNs", mainCpu.get()).put("mainWorkNs", mainWall.get()).put("bitmapNs", bitmapWall.get())
                    .put("sourceConstructorNs", sourceWall.get()).put("styleMutationNs", mutationWall.get())
                    .put("prepareAndSubmitNs", submittedAt.get() - workStarted).put("nextRendererProgressNs", progressAt.get() - submittedAt.get())
                    .put("processBefore", before).put("processAfterSubmit", afterSubmit))
                val cleaned = CountDownLatch(1)
                val cleanupArmed = AtomicBoolean()
                lateinit var cleanupListener: MapView.OnDidFinishRenderingFrameListener
                cleanupListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                    if (cleanupArmed.compareAndSet(true, false)) {
                        view.removeOnDidFinishRenderingFrameListener(cleanupListener); cleaned.countDown()
                    }
                }
                instrumentation.runOnMainSync {
                    view.addOnDidFinishRenderingFrameListener(cleanupListener)
                    style.removeLayer(layerId); style.removeSource(sourceId)
                    check(view.post { cleanupArmed.set(true); map.triggerRepaint() })
                }
                check(cleaned.await(15, TimeUnit.SECONDS))
                preparedRef.set(null); rawRef.set(null); bitmapRef.set(null)
            }
            val record = JSONObject().put("size", size).put("pixels", size * size).put("prepared", preparedMode)
                .put("appSha256", appHash).put("testSha256", testHash).put("fingerprint", Build.FINGERPRINT)
                .put("sizeOrder", sizes.joinToString(",")).put("rawArgbSha256", rawHash).put("bitmapSha256", bitmapHash).put("samples", samples)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "MAPLIBRE_RASTER_COST $record\n") })
        }
    }

    // Frozen b48 conversion. Do not call the new bulk packer or a candidate helper here.
    private fun legacyPixels(mask: FogPixelMask): IntArray {
        val alpha = mask.copyAlpha()
        return IntArray(alpha.size) { index ->
            val unsigned = alpha[index].toInt() and 0xff
            if (unsigned == 0) 0 else (unsigned shl 24) or 0x001F262B
        }
    }

    /** Process counters are noisy deltas, not per-thread allocation counts or peak RSS. */
    private fun counters(): JSONObject {
        val runtime = Runtime.getRuntime()
        fun stat(key: String): Any = runCatching { Debug.getRuntimeStat(key)?.toLongOrNull() }.getOrNull() ?: JSONObject.NULL
        return JSONObject().put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("allocatedBytes", stat("art.gc.bytes-allocated")).put("gcCount", stat("art.gc.gc-count"))
            .put("blockingGcCount", stat("art.gc.blocking-gc-count"))
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun pixelsHash(pixels: IntArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(8192)
        pixels.forEach { pixel ->
            if (buffer.remaining() < 4) { hash.update(buffer.array(), 0, buffer.position()); buffer.clear() }
            buffer.putInt(pixel)
        }
        hash.update(buffer.array(), 0, buffer.position())
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
