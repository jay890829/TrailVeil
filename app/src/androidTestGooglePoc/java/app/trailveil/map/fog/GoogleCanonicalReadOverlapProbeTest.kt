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
import app.trailveil.data.db.RecordingDao
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import java.lang.reflect.InvocationTargetException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only core preparation probe using SDK-visible corners and production coverage planners. */
class GoogleCanonicalReadOverlapProbeTest {
    private data class Window(val phase: String, val south: Double, val north: Double, val interval: LongitudeInterval, val rows: Int)
    private data class Statement(val phase: String, val sql: String, val args: List<Any?>)
    private data class Result(val raster: FogViewportTileRender, val native: FogNativeGeometry?, val nanos: Long)

    @Test fun measureRawOverlapWithoutChangingCanonicalData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("u4Overlap") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        check(appHash == arguments.getString("u4ExpectedApkHash"))
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        val appDatabase = (instrumentation.targetContext.applicationContext as TrailVeilApplication).appContainer.databaseForTesting()
        val fixtureHash = runBlocking(Dispatchers.IO) { fixtureHash(appDatabase) }
        val anchor = DemoTaiwanAnchors.anchors().first()
        val point = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).last().last()
        val center = GeoPoint(point.latitude, point.longitude)
        val coverages = sdkCoverages(center)
        val capture = AtomicBoolean(false)
        val statements = CopyOnWriteArrayList<Statement>()
        val windows = ArrayList<Window>()
        var phase = "off"
        val database = Room.databaseBuilder(instrumentation.targetContext, TrailVeilDatabase::class.java, TrailVeilDatabase.DATABASE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback({ sql, args ->
                if (capture.get() && sql.trimStart().startsWith("SELECT") &&
                    (sql.contains("FROM track_points p") || sql.contains("FROM track_point_cells") || sql.contains("COALESCE(MAX(id), 0)"))) {
                    statements += Statement(phase, sql, args.toList())
                }
            }, Runnable::run).build()
        try {
            runBlocking(Dispatchers.Default) {
                check(fixtureHash(database) == fixtureHash)
                val room = RoomViewportTrackPointReader(database.recordingDao())
                val traced = object : ViewportTrackPointReader by room {
                    override suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> {
                        val readPhase = phase
                        return room.read(south, north, interval).also { windows += Window(readPhase, south, north, interval, it.size) }
                    }
                }
                for (zoom in listOf(12, 14, 16)) for (padding in listOf(0, 2))
                for (cacheState in if (arguments.getString("u4ProductionCost") == "true" && zoom == 12 && padding == 2)
                    listOf("cold", "native-warm", "partial-raster") else listOf("cold")) {
                    val request = FogViewportRequest(center, zoom.toDouble())
                    val coverage = coverages.getValue(zoom)
                    val profile = if (padding == 0) GoogleFogCoverageProfile.DEFAULT else GoogleFogCoverageProfile.ring(padding)
                    val rasterKeys = profile.renderPlanner().plan(coverage).keys
                    val floorKeys = profile.surroundPlanner().plan(coverage).keys
                    // Same floor bounds as prepare; SDK observed-request unions are not injected here.
                    val nativeBounds = FogPocMosaic.layout(floorKeys, 256).anchoredNear(center.longitude).bounds
                    fun coordinator(reader: ViewportTrackPointReader) = FogViewportCoordinator(ViewportTrackDataSource(reader),
                        FogTilePipeline(FogMemoryTileCache(32L * 1024 * 1024), null, FogTileRenderer()::render),
                        nativeGeometryEngine = checkNotNull(fogNativeGeometryEngine()))
                    suspend fun execute(c: FogViewportCoordinator, enabled: Boolean): Result {
                        statements.clear(); windows.clear(); capture.set(enabled)
                        val start = System.nanoTime()
                        phase = "raster"
                        val raster = c.renderTiles(request, rasterKeys)
                        phase = "native"
                        val native = c.renderNativeGeometry(nativeBounds)
                        val elapsed = System.nanoTime() - start
                        capture.set(false); phase = "off"
                        return Result(raster, native, elapsed)
                    }
                    if (arguments.getString("u4ProductionCost") == "true") {
                        val reuse = checkNotNull(arguments.getString("u4ProductionReuse")).toBooleanStrict()
                        // Resolve candidate-only APIs outside timing so the same test APK can run on b50.
                        val type = if (reuse) Class.forName("app.trailveil.data.map.ViewportRawPointMemo") else null
                        val constructor = type?.constructors?.single { it.parameterCount == 5 }
                        val seal = type?.methods?.single { it.name == "seal" && it.parameterCount == 1 }
                        val close = type?.methods?.single { it.name == "close" && it.parameterCount == 1 }
                        val epoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
                        val valid: () -> Boolean = { epoch.value == 0L } // unchanged fixture; same StateFlow read as binding
                        suspend fun action(instance: Any, method: java.lang.reflect.Method) = suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                            try {
                                val result = method.invoke(instance, continuation)
                                if (result === COROUTINE_SUSPENDED) result else Unit
                            } catch (error: InvocationTargetException) { throw checkNotNull(error.cause) }
                        }
                        val pipeline = FogTilePipeline(FogMemoryTileCache(32L * 1024 * 1024), null, FogTileRenderer()::render)
                        val c = FogViewportCoordinator(ViewportTrackDataSource(room), pipeline, nativeGeometryEngine = checkNotNull(fogNativeGeometryEngine()))
                        val envelopeMethod = if (reuse) c.javaClass.methods.single { it.name == "nativeRawReadEnvelope" && it.parameterCount == 2 } else null
                        val previewPlanner = profile.surroundPlanner()
                        var previewBounds: FogTileBounds? = null
                        suspend fun envelope(): ViewportBounds? {
                            // Candidate-only work added by the real binding belongs INSIDE its timing.
                            val previewKeys = previewPlanner.plan(coverage).keys
                            val bounds = FogPocMosaic.layout(previewKeys, 256).anchoredNear(coverage.center.longitude).bounds
                            previewBounds = bounds
                            return suspendCoroutineUninterceptedOrReturn { continuation ->
                                try { checkNotNull(envelopeMethod).invoke(c, bounds, continuation) }
                                catch (error: InvocationTargetException) { throw checkNotNull(error.cause) }
                            }
                        }
                        suspend fun setupCaches() {
                            c.clearDerivedCache()
                            when (cacheState) {
                                "native-warm" -> checkNotNull(c.renderNativeGeometry(nativeBounds))
                                "partial-raster" -> { c.renderTiles(request, rasterKeys); pipeline.invalidate(listOf(rasterKeys.first())) }
                            }
                        }
                        var memoCreated = false
                        suspend fun productionRound(): Result {
                            val start = System.nanoTime()
                            val required = if (reuse) envelope() else null
                            val memo = if (required != null) constructor?.newInstance(valid, 32_768, 2, 4L * 1024 * 1024, required) as? CoroutineContext else null
                            memoCreated = memo != null
                            lateinit var raster: FogViewportTileRender
                            var native: FogNativeGeometry? = null
                            try {
                                withContext(memo ?: EmptyCoroutineContext) {
                                    raster = c.renderTiles(request, rasterKeys)
                                    if (memo != null) action(memo, checkNotNull(seal))
                                    native = c.renderNativeGeometry(nativeBounds)
                                }
                            } finally { if (memo != null) withContext(NonCancellable) { action(memo, checkNotNull(close)) } }
                            return Result(raster, native, System.nanoTime() - start)
                        }
                        val reference = execute(c, false)
                        val expectedMasks = masksHash(reference.raster)
                        val expectedGeometry = geometryHash(reference.native)
                        val samples = JSONArray()
                        repeat(7) { sample ->
                            setupCaches()
                            val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                            val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                            val result = productionRound()
                            val allocatedAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                            val gcAfter = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                            check(masksHash(result.raster) == expectedMasks && geometryHash(result.native) == expectedGeometry)
                            if (reuse) check(previewBounds == nativeBounds)
                            samples.put(JSONObject().put("sample", sample).put("totalNs", result.nanos)
                                .put("allocatedBefore", allocatedBefore).put("allocatedAfter", allocatedAfter)
                                .put("gcBefore", gcBefore).put("gcAfter", gcAfter))
                        }
                        // Untimed query-count control; no SQL capture overhead in the seven cost samples.
                        setupCaches(); statements.clear(); capture.set(true)
                        val control = productionRound()
                        capture.set(false)
                        check(masksHash(control.raster) == expectedMasks && geometryHash(control.native) == expectedGeometry)
                        val pointQueries = statements.count { it.sql.contains("FROM track_points p") }
                        val stamps = statements.count { it.sql.contains("COALESCE(MAX(id), 0)") }
                        if (reuse && (cacheState != "cold" || zoom == 16 || zoom == 14 && padding == 0)) check(stamps == 0)
                        if (reuse && cacheState == "native-warm") check(!memoCreated)
                        val label = "z${zoom}padding${padding}" + if (cacheState == "cold") "" else "-$cacheState"
                        val record = JSONObject().put("case", label).put("reuse", reuse).put("cacheState", cacheState)
                            .put("appSha256", appHash).put("testSha256", testHash).put("fixtureSha256", fixtureHash)
                            .put("fingerprint", Build.FINGERPRINT).put("rasterTiles", rasterKeys.size).put("nativeTiles", floorKeys.size)
                            .put("maskSha256", expectedMasks).put("geometrySha256", expectedGeometry).put("samples", samples)
                            .put("pointQueries", pointQueries).put("stampQueries", stamps).put("memoCreated", memoCreated)
                            .put("scope", "production metadata gate/Room/memo; candidate floor planner/layout/anchor,preview,construction,mux,copy,seal,close included plus reflection; cache setup and SQL-count control outside timing; unchanged fixture; no SDK presentation timing")
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "U4_PRODUCTION $record\n") })
                        continue
                    }
                    if (arguments.getString("u4ReusePilot") == "true") {
                        val pilot = PilotReader(room, database.recordingDao()) { phase }
                        val baselineCoordinator = coordinator(room)
                        val pilotCoordinator = coordinator(pilot)
                        val reference = execute(baselineCoordinator, false)
                        val expectedMasks = masksHash(reference.raster)
                        val expectedGeometry = geometryHash(reference.native)
                        val samples = JSONArray()
                        repeat(7) { sample ->
                            for (reuse in if (sample % 2 == 0) listOf(false, true) else listOf(true, false)) {
                                val c = if (reuse) pilotCoordinator else baselineCoordinator
                                c.clearDerivedCache(); pilot.reset()
                                val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                                val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                                val result = execute(c, false)
                                val allocatedAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                                val gcAfter = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                                check(masksHash(result.raster) == expectedMasks && geometryHash(result.native) == expectedGeometry)
                                samples.put(JSONObject().put("sample", sample).put("reuse", reuse).put("totalNs", result.nanos)
                                    .put("allocatedBefore", allocatedBefore).put("allocatedAfter", allocatedAfter)
                                    .put("gcBefore", gcBefore).put("gcAfter", gcAfter)
                                    .put("hits", pilot.hits).put("stampQueries", pilot.stampQueries).put("retainedRows", pilot.retainedRows))
                                pilot.reset() // request lifetime ends before the next sample
                            }
                        }
                        val record = JSONObject().put("case", "z${zoom}padding${padding}")
                            .put("appSha256", appHash).put("testSha256", testHash).put("fixtureSha256", fixtureHash)
                            .put("fingerprint", Build.FINGERPRINT).put("rasterTiles", rasterKeys.size).put("nativeTiles", floorKeys.size)
                            .put("maskSha256", expectedMasks).put("geometrySha256", expectedGeometry).put("samples", samples)
                            .put("scope", "test-only bounded raw-row pilot; two highwater checks per capture/hit; unchanged canonical fixture; no production adoption or replacement-mutation proof")
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "U4_PILOT $record\n") })
                        continue
                    }
                    val discoveryCoordinator = coordinator(traced)
                    val discovery = execute(discoveryCoordinator, true)
                    val captured = statements.toList()
                    val requested = windows.toList()
                    check(captured.size == requested.size && captured.all { it.sql.contains("FROM track_points p") })
                    check(captured.map { it.phase } == requested.map { it.phase })
                    // All tuple inspection/hash work happens after the measured execution.
                    val replay = captured.map { statement -> rawRead(database, statement) }
                    check(replay.map { it.size } == requested.map { it.rows })
                    for ((index, window) in requested.withIndex()) {
                        val ordinary = room.read(window.south, window.north, window.interval)
                        check(rowsHash(listOf(ordinary)) == rowsHash(listOf(replay[index])))
                    }
                    val rasterRows = replay.filterIndexed { index, _ -> requested[index].phase == "raster" }.flatten()
                    val nativeRows = replay.filterIndexed { index, _ -> requested[index].phase == "native" }.flatten()
                    val rasterIds = rasterRows.map { it.pointId }.toSet()
                    val nativeIds = nativeRows.map { it.pointId }.toSet()
                    val rasterWindows = requested.filter { it.phase == "raster" }
                    val nativeWindows = requested.filter { it.phase == "native" }
                    fun contains(outer: Window, inner: Window) = outer.south <= inner.south && outer.north >= inner.north &&
                        outer.interval.west <= inner.interval.west && outer.interval.east >= inner.interval.east
                    val fullyContained = nativeWindows.filter { inner -> rasterWindows.any { contains(it, inner) } }
                    // Verify that a fully covered raw-row filter actually equals each native SQL result.
                    for ((index, window) in requested.withIndex()) if (window in fullyContained) {
                        val outerIndex = requested.indexOfFirst { it.phase == "raster" && contains(it, window) }
                        val filtered = replay[outerIndex].filter { row -> row.latitude >= window.south && row.latitude <= window.north &&
                            row.longitude >= window.interval.west && row.longitude <= window.interval.east }
                        check(rowsHash(listOf(filtered)) == rowsHash(listOf(replay[index])))
                    }
                    val maskHash = masksHash(discovery.raster)
                    val nativeHash = geometryHash(discovery.native)
                    val warm = execute(discoveryCoordinator, true)
                    check(windows.isEmpty() && statements.isEmpty())
                    check(masksHash(warm.raster) == maskHash && geometryHash(warm.native) == nativeHash)
                    val samples = JSONArray()
                    val plainCoordinator = coordinator(room)
                    val tracedCoordinator = coordinator(traced)
                    repeat(7) { sample ->
                        // Matched AA capture overhead controls with alternating within-pair order.
                        for (enabled in if (sample % 2 == 0) listOf(false, true) else listOf(true, false)) {
                            val c = if (enabled) tracedCoordinator else plainCoordinator
                            c.clearDerivedCache()
                            val result = execute(c, enabled)
                            check(masksHash(result.raster) == maskHash && geometryHash(result.native) == nativeHash)
                            if (enabled) check(windows == requested && statements == captured)
                            samples.put(JSONObject().put("sample", sample).put("capture", enabled).put("totalNs", result.nanos))
                        }
                    }
                    val record = JSONObject().put("case", "z${zoom}padding${padding}").put("appSha256", appHash).put("testSha256", testHash)
                        .put("fixtureSha256", fixtureHash).put("fingerprint", Build.FINGERPRINT)
                        .put("scope", "SDK-visible corners; production coverage planned keys; no observed-request union or SDK presentation timing")
                        .put("rasterTiles", rasterKeys.size).put("nativeTiles", floorKeys.size)
                        .put("rasterQueries", rasterWindows.size).put("nativeQueries", nativeWindows.size)
                        .put("rasterRowOccurrences", rasterRows.size).put("nativeRowOccurrences", nativeRows.size)
                        .put("rasterUniqueRows", rasterIds.size).put("nativeUniqueRows", nativeIds.size)
                        .put("sharedUniqueRows", (rasterIds intersect nativeIds).size)
                        .put("rasterOnlyRows", (rasterIds - nativeIds).size).put("nativeOnlyRows", (nativeIds - rasterIds).size)
                        .put("nativeRepeatedOccurrences", nativeRows.size - nativeIds.size)
                        .put("fullyContainedNativeQueries", fullyContained.size).put("fullyContainedNativeRowOccurrences", fullyContained.sumOf { it.rows })
                        .put("rawRowsSha256", rowsHash(replay)).put("maskSha256", maskHash).put("geometrySha256", nativeHash)
                        .put("fallback", discovery.native == null).put("nativeDiagnostics", discovery.native?.diagnostics ?: discoveryCoordinator.nativeGeometryDescription())
                        .put("warmQueries", 0).put("warmNs", warm.nanos).put("samples", samples)
                        .put("windowHashes", JSONArray(requested.map { hash(ByteBuffer.allocate(32).putDouble(it.south).putDouble(it.north).putDouble(it.interval.west).putDouble(it.interval.east).array()) }))
                        .put("sqlHashes", JSONArray(captured.map { hash(it.sql.toByteArray()) }))
                        .put("argumentHashes", JSONArray(captured.map { hash(it.args.joinToString("|") { arg -> "${arg?.javaClass?.name}:$arg" }.toByteArray()) }))
                        .put("queryRows", JSONArray(requested.map { it.rows }))
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "U4_OVERLAP $record\n") })
                }
                check(fixtureHash(database) == fixtureHash && fixtureHash(appDatabase) == fixtureHash)
            }
        } finally { capture.set(false); database.close() }
    }

    /** Cost prototype only. The fixture is read-only; a product candidate also needs replacement fencing. */
    private class PilotReader(
        private val room: RoomViewportTrackPointReader,
        private val dao: RecordingDao,
        private val phase: () -> String,
    ) : ViewportTrackPointReader by room {
        private data class Entry(val south: Double, val north: Double, val interval: LongitudeInterval, val stamp: Long, val rows: List<ViewportTrackPoint>)
        private val entries = ArrayList<Entry>()
        var hits = 0; private set
        var stampQueries = 0; private set
        var retainedRows = 0; private set
        fun reset() { entries.clear(); hits = 0; stampQueries = 0; retainedRows = 0 }
        private suspend fun stamp(): Long { stampQueries++; return dao.latestPersistedPointId() }
        override suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> {
            val context = currentCoroutineContext()
            context.ensureActive()
            if (phase() == "raster" && entries.size < 2 && retainedRows < 32_768) {
                val before = stamp()
                val rows = room.read(south, north, interval)
                if (rows.size <= 32_768 - retainedRows && before == stamp()) {
                    entries += Entry(south, north, interval, before, rows.toList())
                    retainedRows += rows.size
                }
                return rows
            }
            if (phase() == "native") {
                val entry = entries.firstOrNull { it.south <= south && it.north >= north &&
                    it.interval.west <= interval.west && it.interval.east >= interval.east }
                if (entry != null) {
                    if (entry.stamp == stamp()) {
                        val rows = ArrayList<ViewportTrackPoint>()
                        entry.rows.forEachIndexed { index, row ->
                            if (index % 256 == 0) context.ensureActive()
                            if (row.latitude >= south && row.latitude <= north && row.longitude >= interval.west && row.longitude <= interval.east) rows += row
                        }
                        context.ensureActive()
                        if (entry.stamp == stamp()) { hits++; return rows }
                    }
                    entries.clear(); retainedRows = 0
                }
            }
            return room.read(south, north, interval)
        }
    }

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
                for (zoom in listOf(12, 14, 16)) {
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

    private fun rawRead(database: TrailVeilDatabase, statement: Statement): List<ViewportTrackPoint> =
        database.openHelper.readableDatabase.query(statement.sql, statement.args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ViewportTrackPoint(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2),
                cursor.getLong(3), cursor.getLong(4), cursor.getDouble(5), cursor.getDouble(6))) }
        }

    private fun rowsHash(groups: List<List<ViewportTrackPoint>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(56)
        groups.forEach { rows ->
            digest.update(ByteBuffer.allocate(4).putInt(rows.size).array())
            rows.forEach { row ->
                buffer.clear(); buffer.putLong(row.pointId).putLong(row.sessionId).putLong(row.segmentId).putLong(row.segmentSequence)
                    .putLong(row.pointSequence).putDouble(row.latitude).putDouble(row.longitude)
                digest.update(buffer.array())
            }
        }
        return hex(digest.digest())
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
