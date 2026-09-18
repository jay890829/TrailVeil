package app.trailveil.map.fog

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import app.trailveil.data.map.RoomViewportTrackPointReader
import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPointReader
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.LongitudeInterval
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoExplorationTrack
import app.trailveil.harness.DemoTaiwanAnchors
import app.trailveil.map.fogNativeGeometryEngine
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Common coordinator/Room cost on the dedicated synthetic AVD; not SDK presentation latency. */
class FogNativeFirstCostProbeTest {
    @Test fun nativeColdWarmAndFirstFallbackCost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("trailveilNativeFirstBenchmark") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val apkHash = fileHash(instrumentation.targetContext.applicationInfo.sourceDir)
        check(apkHash == args.getString("trailveilExpectedApkHash"))
        val expectNativeFirst = checkNotNull(args.getString("trailveilExpectNativeFirst")).toBooleanStrict()
        val probeHash = fileHash(instrumentation.context.applicationInfo.sourceDir)
        val container = (instrumentation.targetContext.applicationContext as TrailVeilApplication).appContainer
        val database = container.databaseForTesting()
        val fixtureHash = runBlocking(Dispatchers.IO) {
            val db = database.openHelper.readableDatabase
            val count = db.query("SELECT COUNT(*) FROM track_points").use { check(it.moveToFirst()); it.getLong(0) }
            val demo = db.query("SELECT COUNT(*) FROM track_points WHERE is_mock = 1 AND session_id IN " +
                "(SELECT id FROM recording_sessions WHERE stop_reason = ?)", arrayOf<Any>(DemoExplorationSeeder.DEMO_STOP_REASON))
                .use { check(it.moveToFirst()); it.getLong(0) }
            val sessions = db.query("SELECT COUNT(*) FROM recording_sessions").use { check(it.moveToFirst()); it.getLong(0) }
            check(count == 204_800L && demo == count && sessions == 200L) { "requires the dedicated synthetic fixture" }
            val digest = MessageDigest.getInstance("SHA-256")
            val row = ByteBuffer.allocate(72)
            db.query("SELECT id, session_id, segment_id, sequence, timestamp, latitude, longitude, " +
                "horizontal_accuracy, is_mock FROM track_points ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) {
                    row.clear()
                    for (column in 0..4) row.putLong(cursor.getLong(column))
                    for (column in 5..7) row.putDouble(cursor.getDouble(column))
                    row.putLong(cursor.getLong(8))
                    digest.update(row.array())
                }
            }
            hex(digest.digest())
        }
        val anchor = DemoTaiwanAnchors.anchors().first()
        if (args.getString("trailveilNativeFallbackSweep") == "true") {
            val anchors = DemoTaiwanAnchors.anchors()
            for (anchorIndex in listOf(0, 99, 199)) for (zoom in listOf(10, 11, 12, 13, 14, 16)) for (padding in listOf(1, 2)) {
                val location = anchors[anchorIndex]
                val sample = DemoExplorationTrack.componentsAround(location.latitude, location.longitude).last().last()
                val center = GeoPoint(sample.latitude, sample.longitude)
                val layout = FogPocMosaic.layout(FogViewportTileGrid.around(center, zoom, FogRenderVersions.CURRENT, padding), 256)
                    .anchoredNear(center.longitude)
                val engine = checkNotNull(fogNativeGeometryEngine())
                val reads = AtomicInteger()
                val room = RoomViewportTrackPointReader(database.recordingDao())
                val reader = object : ViewportTrackPointReader by room {
                    override suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> {
                        reads.incrementAndGet()
                        return room.read(south, north, interval)
                    }
                }
                val coordinator = FogViewportCoordinator(ViewportTrackDataSource(reader),
                    FogTilePipeline(FogMemoryTileCache(1), null) { _, _ -> error("geometry-only sweep invoked raster") },
                    nativeGeometryEngine = engine)
                runBlocking(Dispatchers.Default) {
                    val start = System.nanoTime()
                    val cold = coordinator.renderNativeGeometry(layout.bounds)
                    val coldNs = System.nanoTime() - start
                    val coldDescription = engine.description
                    val coldReads = reads.get()
                    val warmStart = System.nanoTime()
                    val warm = coordinator.renderNativeGeometry(layout.bounds)
                    val warmNs = System.nanoTime() - warmStart
                    check((cold == null) == (warm == null))
                    check(cold?.let(::hashGeometry) == warm?.let(::hashGeometry))
                    val record = JSONObject().put("case", "a${anchorIndex}z${zoom}p$padding")
                        .put("apkSha256", apkHash).put("probeSha256", probeHash).put("fixtureSha256", fixtureHash)
                        .put("deviceFingerprint", Build.FINGERPRINT).put("build", args.getString("trailveilBuildLabel"))
                        .put("coldNs", coldNs).put("warmNs", warmNs).put("coldReads", coldReads)
                        .put("warmReads", reads.get() - coldReads).put("fallback", cold == null)
                        .put("reason", if (cold == null) coldDescription else "native-success")
                        .put("coldStages", coldDescription).put("warmStages", engine.description)
                        .put("geometrySha256", cold?.let(::hashGeometry) ?: "none")
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "NATIVE_SWEEP $record\n") })
                }
            }
            return
        }
        val point = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).last().last()
        val zooms = (args.getString("trailveilBenchmarkZooms") ?: "14,16").split(',').map(String::toInt)
        check(zooms in listOf(listOf(14, 16), listOf(12, 14, 16), listOf(13, 14, 16)))
        for (zoom in zooms) for (padding in listOf(1, 2)) {
            val request = FogViewportRequest(GeoPoint(point.latitude, point.longitude), zoom.toDouble())
            val detailed = args.getString("trailveilDetailedGeometry") == "true"
            val processor = SwitchableGeometry(if (detailed) app.trailveil.harness.DetailedTrackRegionGeometryEngine()
                else checkNotNull(fogNativeGeometryEngine()))
            val roomReads = AtomicInteger()
            val roomRows = AtomicLong()
            val roomReaderNanos = AtomicLong()
            val detailedRoom = if (detailed) DetailedRoomReader(database.recordingDao()) else null
            val room: ViewportTrackPointReader = detailedRoom ?: RoomViewportTrackPointReader(database.recordingDao())
            val reader = object : ViewportTrackPointReader by room {
                override suspend fun read(south: Double, north: Double, interval: LongitudeInterval): List<ViewportTrackPoint> {
                    roomReads.incrementAndGet()
                    val started = System.nanoTime()
                    return room.read(south, north, interval).also {
                        roomReaderNanos.addAndGet(System.nanoTime() - started)
                        roomRows.addAndGet(it.size.toLong())
                    }
                }
            }
            val coordinator = FogViewportCoordinator(ViewportTrackDataSource(reader),
                FogTilePipeline(FogMemoryTileCache(16L * 1024L * 1024L), null, FogTileRenderer()::render),
                nativeGeometryEngine = processor, mosaicPaddingTiles = padding)
            val cold = JSONArray(); val warm = JSONArray(); val fallback = JSONArray()
            val read = JSONArray(); val select = JSONArray(); val paint = JSONArray(); val pixels = JSONArray()
            val readCalls = JSONArray(); val rowCounts = JSONArray(); val warmReadCalls = JSONArray(); val batches = JSONArray()
            val coldStages = JSONArray(); val warmStages = JSONArray()
            val readerTimes = JSONArray(); val callbackTimes = JSONArray(); val modelTimes = JSONArray()
            val daoTimes = JSONArray(); val projectionTimes = JSONArray()
            var geometryHash: String? = null
            val reference = runBlocking(Dispatchers.Default) { coordinator.render(request, nativeGeometry = false) }
            val referenceMask = checkNotNull(mask(payload(reference)))
            val expectedMaskHash = hex(MessageDigest.getInstance("SHA-256").digest(referenceMask.copyAlpha()))
            repeat(7) {
                runBlocking(Dispatchers.Default) {
                    coordinator.clearDerivedCache() // isolated derived processor; no canonical deletion
                    processor.fallback = false
                    roomReads.set(0); roomRows.set(0L)
                    roomReaderNanos.set(0L); processor.callbackNanos = 0L
                    detailedRoom?.let { it.daoNanos = 0L; it.projectionNanos = 0L }
                    val work = FogRasterWorkProbe()
                    var start = System.nanoTime()
                    val native = withContext(work) { coordinator.render(request, nativeGeometry = true) }
                    cold.put(System.nanoTime() - start)
                    coldStages.put(checkNotNull(geometry(native)).diagnostics)
                    readerTimes.put(roomReaderNanos.get()); callbackTimes.put(processor.callbackNanos)
                    check(processor.callbackNanos >= roomReaderNanos.get())
                    modelTimes.put(processor.callbackNanos - roomReaderNanos.get())
                    daoTimes.put(detailedRoom?.daoNanos ?: -1L); projectionTimes.put(detailedRoom?.projectionNanos ?: -1L)
                    check(native.keys == reference.keys && geometry(native) != null)
                    val actualGeometryHash = hashGeometry(checkNotNull(geometry(native)))
                    val coldReads = roomReads.get()
                    readCalls.put(coldReads); rowCounts.put(roomRows.get())
                    batches.put(Regex("batchReads=(\\d+)").find(checkNotNull(geometry(native)).diagnostics)?.groupValues?.get(1)?.toInt() ?: 0)
                    check(geometryHash == null || geometryHash == actualGeometryHash)
                    geometryHash = actualGeometryHash
                    val nativeMask = mask(payload(native))
                    check((nativeMask == null) == expectNativeFirst) { "unexpected native representation" }
                    val pixelCount = nativeMask?.let { it.width * it.height } ?: 0
                    pixels.put(pixelCount)
                    read.put(work.readNanos.get()); select.put(work.selectionNanos.get()); paint.put(work.paintNanos.get())
                    if (expectNativeFirst) check(work.readNanos.get() == 0L && work.selectionNanos.get() == 0L && work.paintNanos.get() == 0L)
                    start = System.nanoTime()
                    val reused = coordinator.render(request, nativeGeometry = true)
                    warm.put(System.nanoTime() - start)
                    warmStages.put(checkNotNull(geometry(reused)).diagnostics)
                    warmReadCalls.put(roomReads.get() - coldReads)
                    check(hashGeometry(checkNotNull(geometry(reused))) == actualGeometryHash)
                    processor.fallback = true
                    start = System.nanoTime()
                    val raster = coordinator.render(request, nativeGeometry = true)
                    fallback.put(System.nanoTime() - start)
                    check(geometry(raster) == null && raster.keys == reference.keys)
                    check(mask(payload(raster)) == referenceMask) { "fallback raster changed" }
                }
            }
            val record = JSONObject().put("case", "z${zoom}p$padding").put("build", args.getString("trailveilBuildLabel"))
                .put("apkSha256", apkHash).put("probeSha256", probeHash).put("deviceFingerprint", Build.FINGERPRINT)
                .put("pointFixtureSha256", fixtureHash).put("viewportHash", hex(MessageDigest.getInstance("SHA-256").digest(reference.keys.toString().toByteArray())))
                .put("geometrySha256", geometryHash).put("fallbackMaskSha256", expectedMaskHash).put("nativeFirst", expectNativeFirst)
                .put("coldNativeNs", cold).put("warmNativeNs", warm).put("firstFallbackNs", fallback)
                .put("rasterReadNs", read).put("rasterSelectNs", select).put("rasterPaintNs", paint).put("nativeRasterPixels", pixels)
                .put("roomReads", readCalls).put("roomRows", rowCounts).put("warmRoomReads", warmReadCalls).put("batchReads", batches)
                .put("coldStages", coldStages).put("warmStages", warmStages)
                .put("roomReaderNs", readerTimes).put("nativeReadCallbackNs", callbackTimes).put("modelRemainderNs", modelTimes)
                .put("detailed", detailed).put("daoNs", daoTimes).put("pointProjectionNs", projectionTimes)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "NATIVE_FIRST $record\n") })
        }
    }

    // A single binary must run on sealed b40 (mosaic carrier) and the new presentation carrier.
    private fun payload(render: FogViewportRender): Any = checkNotNull(render.javaClass.methods.single {
        it.parameterCount == 0 && it.name in setOf("getPresentation", "getMosaic")
    }.invoke(render))
    private fun mask(payload: Any): FogPixelMask? = payload.javaClass.methods.firstOrNull { it.name == "getMask" && it.parameterCount == 0 }
        ?.invoke(payload) as? FogPixelMask
    private fun geometry(render: FogViewportRender): FogNativeGeometry? = payload(render).let { payload ->
        payload.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name in setOf("getGeometry", "getNativeGeometry") }
            ?.invoke(payload) as? FogNativeGeometry
    }
    private fun hashGeometry(geometry: FogNativeGeometry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(16)
        fun value(a: Double, b: Double) { bytes.clear(); bytes.putDouble(a).putDouble(b); digest.update(bytes.array()) }
        value(geometry.bounds.westLongitude, geometry.bounds.eastLongitude)
        value(geometry.bounds.southLatitude, geometry.bounds.northLatitude)
        geometry.polygons.forEach { polygon ->
            digest.update(ByteBuffer.allocate(4).putInt(polygon.holes.size).array())
            (listOf(polygon.shell) + polygon.holes).forEach { ring ->
                digest.update(ByteBuffer.allocate(4).putInt(ring.size).array())
                ring.forEach { value(it.latitude, it.longitude) }
            }
        }
        return hex(digest.digest())
    }
    private fun fileHash(path: String): String = File(path).inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16_384)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        hex(digest.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private class SwitchableGeometry(private val delegate: FogNativeGeometryEngine) : FogNativeGeometryEngine by delegate {
        var fallback = false
        var callbackNanos = 0L
        override val description: String get() = if (fallback) "raster-fallback:forced" else delegate.description
        override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle, read: suspend (ViewportBounds) -> List<TrackSegment>): FogNativeGeometry? =
            if (fallback) null else delegate.render(bounds, style) { window ->
                val started = System.nanoTime()
                try { read(window) } finally { callbackNanos += System.nanoTime() - started }
            }
        override suspend fun renderBatched(bounds: FogTileBounds, style: FogRenderStyle,
            read: suspend (ViewportBounds) -> List<TrackSegment>, readBatch: suspend (List<ViewportBounds>) -> List<List<TrackSegment>>): FogNativeGeometry? =
            if (fallback) null else delegate.renderBatched(bounds, style,
                read = { window ->
                    val started = System.nanoTime()
                    try { read(window) } finally { callbackNanos += System.nanoTime() - started }
                },
                readBatch = { windows ->
                    val started = System.nanoTime()
                    try { readBatch(windows) } finally { callbackNanos += System.nanoTime() - started }
                })
    }
}
