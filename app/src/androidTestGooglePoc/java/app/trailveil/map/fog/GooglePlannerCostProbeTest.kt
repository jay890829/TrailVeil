package app.trailveil.map.fog

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Direct planner CPU/allocation probe; no UI, Room, snapshot proving or cover timing is measured. */
class GooglePlannerCostProbeTest {
    @Test fun exactPlansAndWorkerCpuCosts() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("u3Cost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("u3ExpectedApkHash"))
        val order = (args.getString("u3CaseOrder") ?: "tilted,world-band,excluded,clear,sparse").split(',')
        check(order.sorted() == listOf("clear", "excluded", "sparse", "tilted", "world-band"))
        val planner = FogSnapshotVisualProbePlanner()
        for (name in order) {
            val input = input(name)
            val samples = JSONArray()
            var expectedPlanHash: String? = null
            var expectedChecks: Int? = null
            val inputHash = inputHash(input)
            repeat(7) { sample ->
                val record = runBlocking(Dispatchers.Default) {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    val context = currentCoroutineContext()
                    var checks = 0
                    val before = counters()
                    val cpuStart = Debug.threadCpuTimeNanos()
                    val start = System.nanoTime()
                    val plan = planner.plan(input.request, input.masks, input.exclusions) {
                        context.ensureActive(); checks++
                    }
                    val wall = System.nanoTime() - start
                    val cpu = Debug.threadCpuTimeNanos() - cpuStart
                    val after = counters()
                    // Digest all order/coordinate bits outside the measured planner interval.
                    val digest = planHash(plan)
                    check(expectedPlanHash == null || expectedPlanHash == digest)
                    check(expectedChecks == null || expectedChecks == checks)
                    expectedPlanHash = digest; expectedChecks = checks
                    JSONObject().put("sample", sample).put("cpuNs", cpu).put("wallNs", wall)
                        .put("checkpoints", checks).put("planSha256", digest)
                        .put("coverageKeys", plan.coverageKeys.size).put("probeKeys", plan.probesByKey.size)
                        .put("probes", plan.probesByKey.values.sumOf { it.size })
                        .put("blocks", plan.coverageKeys.sumOf { plan.probeBlocks(it).size })
                        .put("blockedKeys", plan.zoneBlockedKeys.size).put("provable", plan.isProvable())
                        .put("processBefore", before).put("processAfter", after)
                }
                samples.put(record)
            }
            val result = JSONObject().put("case", name).put("caseOrder", order.joinToString(","))
                .put("appSha256", appHash).put("testSha256", testHash).put("fingerprint", Build.FINGERPRINT)
                .put("inputSha256", inputHash).put("planSha256", expectedPlanHash).put("samples", samples)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "U3_PLANNER_COST $result\n") })
        }
    }

    private data class Input(val request: FogViewportCoverageRequest, val masks: Map<FogTileKey, FogPixelMask>, val exclusions: List<FogProbeExclusionZone>)

    private fun input(name: String): Input {
        val request = if (name == "tilted" || name == "clear" || name == "sparse") {
            FogViewportCoverageRequest(GeoPoint(0.0, 0.0), 4, GeoPoint(-20.0, -25.0),
                GeoPoint(45.0, -65.0), GeoPoint(35.0, 75.0), GeoPoint(-25.0, 30.0))
        } else {
            val latitude = if (name == "world-band") 1.0 else 70.0
            FogViewportCoverageRequest(GeoPoint(0.0, 0.0), 2, GeoPoint(-latitude, -180.0),
                GeoPoint(latitude, -180.0), GeoPoint(latitude, 180.0), GeoPoint(-latitude, 180.0))
        }
        val keys = if (request.floorZoom == 4) FogViewportTileGrid.around(request.center, 4, FogRenderVersions.CURRENT, paddingTiles = 3).reversed()
            else (1..2).flatMap { y -> (0..3).map { x -> FogTileKey(2, x, y, FogRenderVersions.CURRENT) } }.reversed()
        val masks = LinkedHashMap<FogTileKey, FogPixelMask>()
        keys.forEach { key -> masks[key] = FogPixelMask(128, 128, ByteArray(128 * 128) { index -> when { name == "clear" -> 0; name == "sparse" && index % 97 != 0 && !(index % 128 in 60..67 && index / 128 in 60..67) -> 0; else -> 184.toByte() } }) }
        val exclusions = if (name == "excluded") listOf(FogProbeExclusionZone(-90.0, 90.0, -180.0, 180.0)) else emptyList()
        return Input(request, masks, exclusions)
    }

    private fun inputHash(input: Input): String {
        val hash = HashWriter()
        hash.int(input.request.floorZoom)
        (listOf(input.request.center) + input.request.visibleCorners()).forEach { hash.long(it.latitude.toRawBits()); hash.long(it.longitude.toRawBits()) }
        input.masks.forEach { (key, mask) -> hash.key(key); hash.int(mask.width); hash.int(mask.height); hash.bytes(mask.copyAlpha()) }
        hash.int(input.exclusions.size)
        input.exclusions.forEach { hash.long(it.southLatitude.toRawBits()); hash.long(it.northLatitude.toRawBits()); hash.long(it.westLongitude.toRawBits()); hash.long(it.eastLongitude.toRawBits()) }
        return hash.finish()
    }

    private fun planHash(plan: FogSnapshotVisualProbePlan): String {
        val hash = HashWriter()
        hash.int(if (plan.isProvable()) 1 else 0)
        hash.int(plan.coverageKeys.size); plan.coverageKeys.forEach(hash::key)
        hash.int(plan.probesByKey.size)
        plan.probesByKey.forEach { (key, probes) -> hash.key(key); hash.int(probes.size); probes.forEach(hash::probe) }
        hash.int(plan.zoneBlockedKeys.size); plan.zoneBlockedKeys.forEach(hash::key)
        plan.coverageKeys.forEach { key ->
            hash.key(key); val blocks = plan.probeBlocks(key); hash.int(blocks.size)
            blocks.forEach { hash.int(it.size); it.forEach(hash::probe) }
        }
        return hash.finish()
    }

    private class HashWriter {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val buffer = ByteBuffer.allocate(8192)
        private fun room(bytes: Int) { if (buffer.remaining() < bytes) flush() }
        private fun flush() { digest.update(buffer.array(), 0, buffer.position()); buffer.clear() }
        fun int(value: Int) { room(4); buffer.putInt(value) }
        fun long(value: Long) { room(8); buffer.putLong(value) }
        fun key(key: FogTileKey) { int(key.zoom); int(key.x); int(key.y); int(key.renderVersion) }
        fun probe(probe: FogSnapshotVisualProbe) { key(probe.key); long(probe.latitude.toRawBits()); long(probe.longitude.toRawBits()); int(if (probe.strongNeighbourhood) 1 else 0); int(probe.blockIndex) }
        fun bytes(value: ByteArray) { flush(); digest.update(value) }
        fun finish(): String { flush(); return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } }
    }

    private fun counters(): JSONObject {
        fun stat(key: String): Any = runCatching { Debug.getRuntimeStat(key)?.toLongOrNull() }.getOrNull() ?: JSONObject.NULL
        return JSONObject().put("allocatedBytes", stat("art.gc.bytes-allocated"))
            .put("gcCount", stat("art.gc.gc-count")).put("blockingGcCount", stat("art.gc.blocking-gc-count"))
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

