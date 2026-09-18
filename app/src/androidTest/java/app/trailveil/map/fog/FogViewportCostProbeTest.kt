package app.trailveil.map.fog

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import app.trailveil.harness.DemoExplorationSeeder
import app.trailveil.harness.DemoExplorationTrack
import app.trailveil.harness.DemoTaiwanAnchors
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production factory/coordinator, cache-cold and warm; neither SDK rendering nor startup timing. */
@RunWith(AndroidJUnit4::class)
class FogViewportCostProbeTest {
    @Test fun productionRasterViewportRecordsColdAndWarmCost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("trailveilViewportBenchmark") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val apkHash = fileHash(instrumentation.targetContext.applicationInfo.sourceDir)
        check(apkHash == arguments.getString("trailveilExpectedApkHash"))
        val probeHash = fileHash(instrumentation.context.applicationInfo.sourceDir)
        val expectedWorkers = checkNotNull(arguments.getString("trailveilExpectedWorkers")).toInt()
        val container = (instrumentation.targetContext.applicationContext as TrailVeilApplication).appContainer
        val (runtime, fixtureHash) = runBlocking(Dispatchers.IO) {
            val db = container.databaseForTesting().openHelper.readableDatabase
            val count = db.query("SELECT COUNT(*) FROM track_points").use { check(it.moveToFirst()); it.getLong(0) }
            val demo = db.query("SELECT COUNT(*) FROM track_points WHERE is_mock = 1 AND session_id IN " +
                "(SELECT id FROM recording_sessions WHERE stop_reason = ?)",
                arrayOf<Any>(DemoExplorationSeeder.DEMO_STOP_REASON)).use { check(it.moveToFirst()); it.getLong(0) }
            val sessions = db.query("SELECT COUNT(*) FROM recording_sessions").use { check(it.moveToFirst()); it.getLong(0) }
            check(count == 204_800L && demo == count && sessions == 200L) { "Requires the dedicated synthetic fixture" }
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
            container.fogRuntime() to hex(digest.digest())
        }
        val anchor = DemoTaiwanAnchors.anchors().first()
        val last = DemoExplorationTrack.componentsAround(anchor.latitude, anchor.longitude).last().last()
        val zooms = (arguments.getString("trailveilViewportZooms") ?: "15,14,13,12").split(',').map(String::toInt)
        check(zooms.isNotEmpty() && zooms.distinct().size == zooms.size && zooms.all { it in 0..22 })
        for (zoom in zooms) {
            val request = FogViewportRequest(GeoPoint(last.latitude, last.longitude), zoom.toDouble())
            val coordinator = runtime.viewportCoordinator
            val projectionEnabled = try {
                coordinator.javaClass.getDeclaredField("rasterProjectionEnabled").apply { isAccessible = true }.getBoolean(coordinator)
            } catch (_: NoSuchFieldException) { false } // The sealed scalar baseline has no opt-in field.
            arguments.getString("trailveilExpectRasterProjection")?.let { expected ->
                check(projectionEnabled == expected.toBooleanStrict()) { "unexpected projection factory route" }
            }
            val reference = runBlocking(Dispatchers.Default) {
                coordinator.clearDerivedCache()
                coordinator.render(request, nativeGeometry = false)
            }
            val cold = JSONArray()
            val warm = JSONArray()
            val paint = JSONArray()
            val read = JSONArray()
            val selection = JSONArray()
            val workers = JSONArray()
            repeat(7) {
                val work = FogRasterWorkProbe()
                runBlocking(Dispatchers.Default + work) {
                    // Derived fog cache only; never deletes a canonical Room point/session.
                    coordinator.clearDerivedCache()
                    val start = System.nanoTime()
                    val rendered = coordinator.render(request, nativeGeometry = false)
                    cold.put(System.nanoTime() - start)
                    check(rendered.keys == reference.keys && rendered.rasterMaskForProbe() == reference.rasterMaskForProbe())
                    check(rendered.queryBounds != null)
                    paint.put(work.paintNanos.get())
                    read.put(work.readNanos.get())
                    selection.put(work.selectionNanos.get())
                    // The sealed serial baseline predates this diagnostic getter. Missing means
                    // legacy serial, never "unknown accepted as parallel"; the expected value is checked.
                    val getter = work.javaClass.methods.firstOrNull { method -> method.name == "getParallelism" && method.parameterCount == 0 }
                    val actualWorkers = getter?.let { method -> (method.invoke(work) as AtomicInteger).get() } ?: 1
                    check(actualWorkers == expectedWorkers) { "Unexpected configured worker count" }
                    workers.put(actualWorkers)
                    val warmStart = System.nanoTime()
                    val reused = coordinator.render(request, nativeGeometry = false)
                    warm.put(System.nanoTime() - warmStart)
                    check(reused.queryBounds == null && reused.rasterMaskForProbe() == reference.rasterMaskForProbe())
                }
            }
            val record = JSONObject().put("case", "z$zoom")
                .put("projectionEnabled", projectionEnabled)
                .put("build", arguments.getString("trailveilBuildLabel"))
                .put("apkSha256", apkHash).put("probeSha256", probeHash)
                .put("deviceFingerprint", Build.FINGERPRINT).put("pointFixtureSha256", fixtureHash)
                .put("viewportHash", hex(MessageDigest.getInstance("SHA-256").digest(reference.keys.toString().toByteArray())))
                .put("maskSha256", hex(MessageDigest.getInstance("SHA-256").digest(reference.rasterMaskForProbe().copyAlpha())))
                .put("tileCount", reference.keys.size).put("workers", workers)
                .put("coldWallNs", cold).put("warmWallNs", warm).put("paintNs", paint)
                .put("readNs", read).put("selectionNs", selection)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "VIEWPORT_CPU $record\n") })
        }
    }

    /** Same probe binary also runs against the sealed pre-presentation baseline. */
    private fun FogViewportRender.rasterMaskForProbe(): FogPixelMask {
        val getter = javaClass.methods.single { it.parameterCount == 0 && it.name in setOf("getPresentation", "getMosaic") }
        val payload = getter.invoke(this)
        return payload.javaClass.getMethod("getMask").invoke(payload) as FogPixelMask
    }

    private fun fileHash(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buffer = ByteArray(16_384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
