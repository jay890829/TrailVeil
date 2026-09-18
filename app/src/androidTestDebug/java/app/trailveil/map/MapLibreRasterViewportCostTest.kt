package app.trailveil.map

import android.os.Process
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.TrailVeilApplication
import app.trailveil.harness.*
import app.trailveil.map.fog.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Actual raster surface cost; hash bounds and every alpha byte only after installed endpoint. */
@RunWith(AndroidJUnit4::class)
class MapLibreRasterViewportCostTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @Test fun actualRasterReturnAndMissCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("x4UiCost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("x4ExpectedApkHash"))
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        val zoom = checkNotNull(args.getString("x4Zoom")).toInt().also { check(it in listOf(8, 12, 14)) }
        val oldArm = MapLibreFogArm.stored(instrumentation.targetContext)
        val oldActive = MapLibreVectorFogState.activeArm
        val oldNative = MapLibreVectorFogState.trackEnabled
        val oldVector = MapLibreVectorFogState.enabled
        val shown = mutableStateOf(true)
        var contentStarted = false
        var coordinator: FogViewportCoordinator? = null
        try {
            MapLibreFogArm.store(instrumentation.targetContext, MapLibreFogArm.RING_2)
            val container = (instrumentation.targetContext.applicationContext as TrailVeilApplication).appContainer
            val (runtime, fixtureHash) = runBlocking(Dispatchers.IO) {
                val db = container.databaseForTesting().openHelper.readableDatabase
                val count = db.query("SELECT COUNT(*) FROM track_points").use { check(it.moveToFirst()); it.getLong(0) }
                val demo = db.query("SELECT COUNT(*) FROM track_points WHERE is_mock = 1 AND session_id IN " +
                    "(SELECT id FROM recording_sessions WHERE stop_reason = ?)", arrayOf<Any>(DemoExplorationSeeder.DEMO_STOP_REASON))
                    .use { check(it.moveToFirst()); it.getLong(0) }
                val sessions = db.query("SELECT COUNT(*) FROM recording_sessions").use { check(it.moveToFirst()); it.getLong(0) }
                check(count == 204_800L && demo == count && sessions == 200L) { "requires the dedicated synthetic fixture" }
                val digest = MessageDigest.getInstance("SHA-256")
                val row = ByteBuffer.allocate(72)
                db.query("SELECT id, session_id, segment_id, sequence, timestamp, latitude, longitude, horizontal_accuracy, is_mock FROM track_points ORDER BY id").use { cursor ->
                    while (cursor.moveToNext()) {
                        row.clear()
                        for (column in 0..4) row.putLong(cursor.getLong(column))
                        for (column in 5..7) row.putDouble(cursor.getDouble(column))
                        row.putLong(cursor.getLong(8)); digest.update(row.array())
                    }
                }
                container.fogRuntime() to hex(digest.digest())
            }
            val liveCoordinator = runtime.viewportCoordinator
            coordinator = liveCoordinator
            MapLibreVectorFogState.activeArm = MapLibreFogArm.RING_2
            MapLibreVectorFogState.trackEnabled = false
            MapLibreVectorFogState.enabled = false
            val anchor = DemoTaiwanAnchors.anchors().first()
            val last = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).last().last()
            val tile = WebMercator.tile(GeoPoint(last.latitude, last.longitude), zoom)
            val count = (1 shl zoom).toDouble()
            val center = GeoPoint(WebMercator.latitudeAtNormalizedY((tile.y + 0.5) / count), (tile.x + 0.5) / count * 360.0 - 180.0)
            check(liveCoordinator.footprint(FogViewportRequest(center, zoom.toDouble())).keys.size == 25)
            val view = AtomicReference<MapView>()
            val latest = AtomicReference<FogViewportRender>()
            val renderCount = AtomicInteger()
            val endpoints = CopyOnWriteArrayList<Endpoint>()
            val installed = AtomicReference<InstalledFogCoverageSnapshot>()
            val composed = AtomicReference<ComposedFogCoverageSnapshot>()
            val traces = CopyOnWriteArrayList<FogViewportRequestTrace>()
            val failures = CopyOnWriteArrayList<Throwable>()
            composeRule.setContent {
                if (shown.value) TrailVeilMapSurface(modifier = Modifier.fillMaxSize(),
                    provider = MapProviderConfiguration("x4-native-opportunity", "https://tiles.invalid/x4-opportunity"),
                    fallbackTimeoutMillis = 100L, fogRuntime = runtime, fogRequired = true,
                    cameraRequest = MapCameraRequest(1L, center, zoom = zoom.toDouble()),
                    onMapViewCreatedForTesting = view::set,
                    onFogRendered = {
                        val wall = System.nanoTime(); val cpu = Process.getElapsedCpuTime()
                        endpoints += Endpoint(wall, cpu, checkNotNull(installed.get()).generation, it)
                        latest.set(it); renderCount.incrementAndGet()
                    },
                    onFogFailure = { failures += it },
                    onFogCoverageInstalledForTesting = installed::set,
                    onFogCoverageStateComposedForTesting = composed::set,
                    onFogViewportRequestedForTesting = { traces += it })
            }
            contentStarted = true
            composeRule.waitUntil(20_000) { view.get() != null }
            val mapRef = AtomicReference<MapLibreMap>()
            val ready = CountDownLatch(1)
            instrumentation.runOnMainSync { view.get().getMapAsync { mapRef.set(it); ready.countDown() } }
            check(ready.await(20, TimeUnit.SECONDS))
            val map = checkNotNull(mapRef.get())
            fun awaitSettled(target: GeoPoint? = null, after: Long? = null) {
                composeRule.waitUntil(30_000) {
                    check(failures.isEmpty()) { "canonical surface failure" }
                    composeRule.runOnIdle {
                        val pose = map.cameraPosition
                        val current = installed.get()
                        val composition = composed.get()
                        val cameraMatches = target == null || (kotlin.math.abs(pose.target!!.latitude - target.latitude) < 1e-7 &&
                            kotlin.math.abs(pose.target!!.longitude - target.longitude) < 1e-7)
                        cameraMatches && kotlin.math.abs(pose.zoom - zoom) < 1e-6 && map.style?.isFullyLoaded == true &&
                            current != null && (after == null || current.generation > after) &&
                            composition?.generation == current.generation && composition.coverageInstalled &&
                            latest.get()?.presentation is FogTileMosaic &&
                            traces.lastOrNull()?.generation == current.generation
                    } && composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover).fetchSemanticsNodes().isEmpty()
                }
                composeRule.waitForIdle()
                Thread.sleep(300)
            }
            awaitSettled(center)
            val bypasses = JSONArray()
            repeat(2) { index ->
                bestEffortClearStuckInjectedPointers()
                awaitSettled()
                val beforeRenders = renderCount.get()
                val beforeTraces = traces.size
                val beforeFootprint = composeRule.runOnIdle { liveCoordinator.footprint(FogViewportRequest(
                    GeoPoint(map.cameraPosition.target!!.latitude, map.cameraPosition.target!!.longitude), map.cameraPosition.zoom)) }
                gentlePan(view.get(), index % 2 == 0)
                composeRule.waitUntil(15_000) { traces.drop(beforeTraces).any { it.trigger.name == "CAMERA_IDLE_REUSED" } }
                awaitSettled()
                val afterFootprint = composeRule.runOnIdle { liveCoordinator.footprint(FogViewportRequest(
                    GeoPoint(map.cameraPosition.target!!.latitude, map.cameraPosition.target!!.longitude), map.cameraPosition.zoom)) }
                check(beforeFootprint == afterFootprint)
                check(renderCount.get() == beforeRenders) { "U1 should bypass the engine" }
                bypasses.put(JSONObject().put("gesture", index).put("newInstalls", 0).put("idleReused", true))
            }
            val rows = JSONArray()
            val orders = if (args.getString("x4Order") == "reverse") listOf("miss", "return") else listOf("return", "miss")
            for (kind in orders) {
                val parked = GeoPoint(center.latitude, center.longitude - 360.0 / count)
                var prior = installed.get().generation
                instrumentation.runOnMainSync { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(parked.latitude, parked.longitude), zoom.toDouble())) }
                awaitSettled(parked, prior)
                runBlocking(Dispatchers.IO) { liveCoordinator.clearDerivedCache() }
                val offsets = if (kind == "return") listOf(0, 1, 2, 1, 0) else listOf(0, 1, 2, 3, 4)
                val hashes = ArrayList<String>()
                for ((index, offset) in offsets.withIndex()) {
                    val target = GeoPoint(center.latitude, center.longitude + offset * 360.0 / count)
                    val expected = liveCoordinator.footprint(FogViewportRequest(target, zoom.toDouble()))
                    prior = installed.get().generation
                    endpoints.clear()
                    val beforeEndpoint = endpoints.size
                    val beforeTrace = traces.size
                    val cpuStart = Process.getElapsedCpuTime(); val start = System.nanoTime()
                    instrumentation.runOnMainSync { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), zoom.toDouble())) }
                    awaitSettled(target, prior)
                    val current = checkNotNull(installed.get())
                    val endpoint = endpoints.drop(beforeEndpoint).single { it.generation == current.generation }
                    check(expected.matches(endpoint.render))
                    check(endpoint.render === latest.get())
                    val mosaic = endpoint.render.presentation as FogTileMosaic
                    // No deep observer or output hash runs before this completed installed endpoint.
                    val outputHash = mosaicHash(mosaic); hashes += outputHash
                    val row = JSONObject().put("case", "maplibre-raster-z$zoom-5x5-$kind").put("step", index + 1)
                        .put("tileOffset", offset).put("appSha256", appHash).put("testSha256", testHash)
                        .put("fixtureSha256", fixtureHash).put("fingerprint", Build.FINGERPRINT)
                        .put("commandToInstalledNs", endpoint.wall - start).put("processCpuMs", endpoint.cpu - cpuStart)
                        .put("outputSha256", outputHash).put("diagnostics", "raster")
                        .put("pixels", mosaic.mask.width * mosaic.mask.height)
                        .put("generation", endpoint.generation).put("newInstalls", endpoints.size - beforeEndpoint)
                        .put("requests", JSONArray(traces.drop(beforeTrace).map { trace -> JSONObject()
                            .put("generation", trace.generation).put("trigger", trace.trigger.name) }))
                    rows.put(row)
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Y12_RASTER_UI_ROW $row\n") })
                }
                if (kind == "return") { check(hashes[0] == hashes[4]); check(hashes[1] == hashes[3]) }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Y12_RASTER_UI_DONE ${JSONObject()
                .put("rows", rows.length()).put("u1Bypasses", bypasses).put("appSha256", appHash)
                .put("testSha256", testHash).put("fixtureSha256", fixtureHash)
                .put("method", "command to complete canonical installation and prior-generation retirement; excludes composed cover and frames")}\n") })
        } finally {
            try {
                if (contentStarted) {
                    composeRule.runOnIdle { shown.value = false }
                    composeRule.waitForIdle()
                }
                coordinator?.let { c ->
                    if (contentStarted) composeRule.waitUntil(10_000) { !c.isLockedForTesting }
                }
            } finally {
                MapLibreFogArm.store(instrumentation.targetContext, oldArm)
                MapLibreVectorFogState.activeArm = oldActive
                MapLibreVectorFogState.trackEnabled = oldNative
                MapLibreVectorFogState.enabled = oldVector
            }
        }
    }

    private data class Endpoint(val wall: Long, val cpu: Long, val generation: Long, val render: FogViewportRender)
    private fun mosaicHash(mosaic: FogTileMosaic): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val b = mosaic.bounds
        digest.update(listOf(b.westLongitude, b.southLatitude, b.eastLongitude, b.northLatitude)
            .joinToString(";") { it.toRawBits().toString() }.toByteArray())
        digest.update(";${mosaic.mask.width};${mosaic.mask.height};${mosaic.tileCount};".toByteArray())
        digest.update(mosaic.mask.copyAlpha())
        return hex(digest.digest())
    }

    private fun gentlePan(view: MapView, forward: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val center = FloatArray(2)
        instrumentation.runOnMainSync {
            val xy = IntArray(2); view.getLocationOnScreen(xy)
            center[0] = xy[0] + view.width / 2f; center[1] = xy[1] + view.height / 2f
        }
        val direction = if (forward) 1 else -1
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, center[1], 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            val accepted = try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
            check(accepted) { "gesture injection rejected" }
        }
        var ended = false
        try {
            send(MotionEvent.ACTION_DOWN, center[0] - direction * 24f)
            repeat(8) { i -> send(MotionEvent.ACTION_MOVE, center[0] + direction * (-24f + 48f * (i + 1) / 8f)); SystemClock.sleep(24) }
            send(MotionEvent.ACTION_UP, center[0] + direction * 24f); ended = true
        } finally { if (!ended) bestEffortClearStuckInjectedPointers() }
    }
    private fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
