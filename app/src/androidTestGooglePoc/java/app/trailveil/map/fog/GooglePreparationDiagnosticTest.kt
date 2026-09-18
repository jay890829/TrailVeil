package app.trailveil.map.fog

import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import app.trailveil.data.db.TrailVeilDatabase
import app.trailveil.data.map.*
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoExplorationTrack
import app.trailveil.harness.DemoTaiwanAnchors
import app.trailveil.map.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Diagnostic only: no product/data changes. Counters never stand in for per-operation allocations. */
class GooglePreparationDiagnosticTest {
    private fun counters(): JSONObject {
        val values = JSONObject()
        for (name in listOf("bytes-allocated", "bytes-freed", "gc-count", "gc-time", "blocking-gc-count", "blocking-gc-time")) {
            values.put(name, Debug.getRuntimeStat("art.gc.$name")?.toLongOrNull() ?: JSONObject.NULL)
        }
        return values.put("processCpuMs", android.os.Process.getElapsedCpuTime()).put("atNs", System.nanoTime())
    }

    @Test fun phaseAndContinuousBoundaryControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("v1Diagnostic") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("v1ExpectedApkHash"))
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        val both = args.getString("v1Modes") == "both"
        val modes = if (both) listOf(false, true) else listOf(false)
        val database = (instrumentation.targetContext.applicationContext as TrailVeilApplication).appContainer.databaseForTesting()
        val fixture = runBlocking(Dispatchers.IO) { fixtureHash(database) }
        val anchor = DemoTaiwanAnchors.anchors().first()
        val sample = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).last().last()
        val center = GeoPoint(sample.latitude, sample.longitude)
        val coverages = sdkCoverages(center)
        val memoType = if (both) Class.forName("app.trailveil.data.map.ViewportRawPointMemo") else null
        val constructor = memoType?.constructors?.single { it.parameterCount == 5 }
        val seal = memoType?.methods?.single { it.name == "seal" && it.parameterCount == 1 }
        val close = memoType?.methods?.single { it.name == "close" && it.parameterCount == 1 }
        suspend fun invoke(instance: Any, method: java.lang.reflect.Method, vararg arguments: Any): Any? =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                try { method.invoke(instance, *arguments, continuation) }
                catch (error: InvocationTargetException) { throw checkNotNull(error.cause) }
            }
        runBlocking(Dispatchers.Default) {
            for (zoom in listOf(14, 16)) {
                val coverage = coverages.getValue(zoom)
                val profile = GoogleFogCoverageProfile.DEFAULT
                val planner = profile.surroundPlanner()
                val keys = profile.renderPlanner().plan(coverage).keys
                val nativeBounds = FogPocMosaic.layout(planner.plan(coverage).keys, 256).anchoredNear(center.longitude).bounds
                val request = FogViewportRequest(center, zoom.toDouble())
                fun coordinator() = FogViewportCoordinator(ViewportTrackDataSource(RoomViewportTrackPointReader(database.recordingDao())),
                    FogTilePipeline(FogMemoryTileCache(32L * 1024 * 1024), null, FogTileRenderer()::render),
                    nativeGeometryEngine = checkNotNull(fogNativeGeometryEngine()))
                val coordinators = modes.associateWith { coordinator() }
                var expectedMask: String? = null
                var expectedGeometry: String? = null
                // Equal one-time untimed initialisation for BOTH coordinator instances.
                for (c in coordinators.values) {
                    val mask = masksHash(c.renderTiles(request, keys))
                    val geometry = geometryHash(c.renderNativeGeometry(nativeBounds))
                    check(expectedMask == null || expectedMask == mask); expectedMask = mask
                    check(expectedGeometry == null || expectedGeometry == geometry); expectedGeometry = geometry
                }
                val epoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
                val valid: () -> Boolean = { epoch.value == 0L }
                val envelopeMethod = if (both) coordinators.getValue(true).javaClass.methods.single {
                    it.name == "nativeRawReadEnvelope" && it.parameterCount == 2
                } else null
                val records = JSONArray()
                val batchBefore = counters()
                repeat(12) { round ->
                    val ordered = if (round % 2 == 0) modes else modes.reversed()
                    for ((order, reuse) in ordered.withIndex()) {
                        val c = coordinators.getValue(reuse)
                        c.clearDerivedCache()
                        val before = counters()
                        val phases = linkedMapOf<String, Long>()
                        lateinit var raster: FogViewportTileRender
                        var native: FogNativeGeometry? = null
                        val totalStart = System.nanoTime()
                        var memo: CoroutineContext? = null
                        if (reuse) {
                            var start = System.nanoTime()
                            val preview = FogPocMosaic.layout(planner.plan(coverage).keys, 256).anchoredNear(coverage.center.longitude).bounds
                            phases["floorPlan"] = System.nanoTime() - start
                            start = System.nanoTime()
                            val envelope = invoke(c, checkNotNull(envelopeMethod), preview) as? ViewportBounds
                            phases["envelope"] = System.nanoTime() - start
                            start = System.nanoTime()
                            if (envelope != null) memo = constructor?.newInstance(valid, 32_768, 2, 4L * 1024 * 1024, envelope) as? CoroutineContext
                            phases["construct"] = System.nanoTime() - start
                        }
                        try {
                            withContext(memo ?: EmptyCoroutineContext) {
                                var start = System.nanoTime()
                                raster = c.renderTiles(request, keys)
                                phases["raster"] = System.nanoTime() - start
                                start = System.nanoTime()
                                if (memo != null) invoke(checkNotNull(memo), checkNotNull(seal))
                                phases["seal"] = System.nanoTime() - start
                                start = System.nanoTime()
                                native = c.renderNativeGeometry(nativeBounds)
                                phases["native"] = System.nanoTime() - start
                            }
                        } finally {
                            val start = System.nanoTime()
                            if (memo != null) withContext(NonCancellable) { invoke(checkNotNull(memo), checkNotNull(close)) }
                            phases["close"] = System.nanoTime() - start
                        }
                        val total = System.nanoTime() - totalStart
                        val after = counters()
                        check(phases.values.sum() <= total)
                        phases["orchestration"] = total - phases.values.sum()
                        check(phases.values.sum() == total)
                        check(masksHash(raster) == expectedMask && geometryHash(native) == expectedGeometry)
                        val afterValidation = counters()
                        records.put(JSONObject().put("round", round).put("order", order).put("reuse", reuse)
                            .put("totalNs", total).put("phasesNs", JSONObject(phases as Map<*, *>))
                            .put("before", before).put("after", after).put("afterValidation", afterValidation))
                    }
                }
                val record = JSONObject().put("case", "z${zoom}default").put("appSha256", appHash).put("testSha256", testHash)
                    .put("fixtureSha256", fixture).put("maskSha256", expectedMask).put("geometrySha256", expectedGeometry)
                    .put("fingerprint", Build.FINGERPRINT).put("batchBefore", batchBefore).put("batchAfter", counters())
                    .put("records", records).put("scope", "phase wall clocks; process counters include other threads and separate validation boundaries; twelve balanced pairs, first sample kept")
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "V1_PHASE $record\n") })
            }
            check(fixtureHash(database) == fixture)
        }
    }

    @Test fun knownLiveAllocationCounterCalibration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("v1Diagnostic") == "true")
        val samples = JSONArray()
        try {
            repeat(24) { round ->
                val before = counters()
                val buffers = Array(4) { ByteArray(256 * 1024) }
                buffers.forEachIndexed { index, bytes -> bytes[0] = (round + index).toByte(); bytes[bytes.lastIndex] = 1 }
                liveBuffers = buffers // publish all allocations so they cannot be removed as unused
                val after = counters()
                check(liveBuffers.sumOf { it.size } == 1_048_576)
                check(liveBuffers.all { it.last() == 1.toByte() })
                samples.put(JSONObject().put("round", round).put("knownLivePayloadBytes", 1_048_576)
                    .put("before", before).put("after", after))
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "V1_CALIBRATION " + JSONObject()
                .put("fingerprint", Build.FINGERPRINT).put("samples", samples) + "\n") })
        } finally { liveBuffers = emptyArray() }
    }
    companion object { @Volatile private var liveBuffers: Array<ByteArray> = emptyArray() }

    private fun sdkCoverages(center: GeoPoint): Map<Int, FogViewportCoverageRequest> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val map = AtomicReference<GoogleMap>()
        val view = AtomicReference<MapView>()
        GoogleMapSurfaceTestHooks.reset()
        GoogleMapSurfaceTestHooks.fogRequired = false
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.onMapReady.set { map.set(it); ready.countDown() }
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { view.set(it) }
        val results = linkedMapOf<Int, FogViewportCoverageRequest>()
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                check(ready.await(20, TimeUnit.SECONDS)) { "SDK map unavailable" }
                for (zoom in listOf(14, 16)) {
                    val idle = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        val sdk = checkNotNull(map.get())
                        sdk.setOnCameraIdleListener { idle.countDown() }
                        sdk.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, center.longitude), zoom.toFloat()))
                    }
                    check(idle.await(20, TimeUnit.SECONDS)) { "SDK camera did not settle" }
                    fun snapshot(): FogViewportCoverageRequest {
                        var result: FogViewportCoverageRequest? = null
                        instrumentation.runOnMainSync {
                            val sdk = checkNotNull(map.get())
                            check(view.get().width > 0 && view.get().height > 0)
                            check(sdk.cameraPosition.zoom == zoom.toFloat() && sdk.cameraPosition.tilt == 0f && sdk.cameraPosition.bearing == 0f)
                            val region = sdk.projection.visibleRegion
                            fun point(value: LatLng) = GeoPoint(value.latitude, value.longitude)
                            result = FogViewportCoverageRequest(point(sdk.cameraPosition.target), zoom,
                                point(region.nearLeft), point(region.farLeft), point(region.farRight), point(region.nearRight))
                        }
                        return checkNotNull(result)
                    }
                    val first = snapshot()
                    Thread.sleep(200)
                    check(first == snapshot()) { "SDK footprint changed during capture" }
                    results[zoom] = first
                }
                instrumentation.runOnMainSync { map.get().setOnCameraIdleListener(null) }
            }
        } finally { GoogleMapSurfaceTestHooks.reset() }
        return results
    }

    private fun masksHash(render: FogViewportTileRender): String {
        val digest = MessageDigest.getInstance("SHA-256")
        render.tiles.forEach { digest.update(it.key.toString().toByteArray()); digest.update(it.mask.copyAlpha()) }
        return hex(digest.digest())
    }
    private fun geometryHash(geometry: FogNativeGeometry?): String {
        if (geometry == null) return "none"
        val digest = MessageDigest.getInstance("SHA-256")
        fun pair(a: Double, b: Double) { digest.update(ByteBuffer.allocate(16).putDouble(a).putDouble(b).array()) }
        pair(geometry.bounds.westLongitude, geometry.bounds.eastLongitude)
        pair(geometry.bounds.southLatitude, geometry.bounds.northLatitude)
        geometry.polygons.forEach { polygon ->
            digest.update(ByteBuffer.allocate(4).putInt(polygon.holes.size).array())
            (listOf(polygon.shell) + polygon.holes).forEach { ring ->
                digest.update(ByteBuffer.allocate(4).putInt(ring.size).array())
                ring.forEach { pair(it.latitude, it.longitude) }
            }
        }
        return hex(digest.digest())
    }
    private fun fixtureHash(database: TrailVeilDatabase): String {
        val sql = database.openHelper.readableDatabase
        val count = sql.query("SELECT COUNT(*) FROM track_points").use { check(it.moveToFirst()); it.getLong(0) }
        val demo = sql.query("SELECT COUNT(*) FROM track_points WHERE is_mock=1 AND session_id IN " +
            "(SELECT id FROM recording_sessions WHERE stop_reason=?)", arrayOf<Any>(DemoExplorationSeeder.DEMO_STOP_REASON))
            .use { check(it.moveToFirst()); it.getLong(0) }
        val sessions = sql.query("SELECT COUNT(*) FROM recording_sessions").use { check(it.moveToFirst()); it.getLong(0) }
        check(count == 204_800L && demo == count && sessions == 200L) { "requires dedicated 200-session synthetic fixture; no reseed permitted" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(80)
        sql.query("SELECT p.id,p.session_id,p.segment_id,s.sequence,p.sequence,p.timestamp,p.latitude,p.longitude,p.horizontal_accuracy,p.is_mock " +
            "FROM track_points p JOIN track_segments s ON s.id=p.segment_id AND s.session_id=p.session_id ORDER BY p.id").use { cursor ->
            while (cursor.moveToNext()) {
                buffer.clear()
                for (column in 0..5) buffer.putLong(cursor.getLong(column))
                for (column in 6..8) buffer.putDouble(cursor.getDouble(column))
                buffer.putLong(cursor.getLong(9)); digest.update(buffer.array())
            }
        }
        return hex(digest.digest())
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
