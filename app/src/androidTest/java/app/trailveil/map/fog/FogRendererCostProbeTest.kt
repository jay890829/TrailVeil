package app.trailveil.map.fog

import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in pure renderer timing on deterministic synthetic inputs, never a device-data query. */
@RunWith(AndroidJUnit4::class)
class FogRendererCostProbeTest {
    @Test fun deterministicMasksRecordCpuCost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("trailveilRasterCpuBenchmark") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        fun fileHash(path: String): String {
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
        val apkHash = fileHash(instrumentation.targetContext.applicationInfo.sourceDir)
        check(apkHash == arguments.getString("trailveilExpectedApkHash"))
        val probeHash = fileHash(instrumentation.context.applicationInfo.sourceDir)
        val key = FogTileKey(16, 32768, 32768, FogRenderVersions.CURRENT)
        fun point(x: Double, y: Double): GeoPoint = GeoPoint(
            WebMercator.latitudeAtNormalizedY((key.y + y / 256.0) / 65536.0),
            (key.x + x / 256.0) / 65536.0 * 360.0 - 180.0,
        )
        val sparse = List(64) { index ->
            TrackSegment(index, listOf(point((index * 37 % 256).toDouble(), (index * 71 % 256).toDouble())))
        }
        val overlap = listOf(TrackSegment(0, List(5_000) { index -> point(if (index % 2 == 0) -10.0 else 266.0, 128.0) }))
        val fill = (-1..33).flatMap { row -> (-1..33).map { column ->
            TrackSegment(row * 35 + column + 36, listOf(point(column * 8.0, row * 8.0)))
        } }
        val full = fill + overlap
        val cases = linkedMapOf("empty" to emptyList(), "sparse" to sparse, "overlap" to overlap, "full" to full)
        for ((name, tracks) in cases) {
            val renderer = FogTileRenderer()
            val expected = renderer.render(key, tracks)
            repeat(3) { check(renderer.render(key, tracks) == expected) }
            val walls = JSONArray()
            val cpus = JSONArray()
            repeat(7) {
                val cpuStart = Debug.threadCpuTimeNanos()
                val wallStart = System.nanoTime()
                val actual = renderer.render(key, tracks)
                walls.put(System.nanoTime() - wallStart)
                cpus.put(Debug.threadCpuTimeNanos() - cpuStart)
                check(actual == expected) { "Nondeterministic renderer output" }
            }
            val record = JSONObject().put("case", name)
                .put("build", arguments.getString("trailveilBuildLabel"))
                .put("apkSha256", apkHash).put("probeSha256", probeHash)
                .put("deviceFingerprint", Build.FINGERPRINT)
                .put("maskSha256", hex(MessageDigest.getInstance("SHA-256").digest(expected.copyAlpha())))
                .put("wallNs", walls).put("threadCpuNs", cpus)
                .put("points", tracks.sumOf { it.points.size })
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "RASTER_CPU $record\n") })
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
