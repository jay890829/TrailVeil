package app.trailveil.data.map

import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual APK orderPoints method; synthetic rows, no Room or map-surface timing. */
class ViewportOrderingCostTest {
    @Test fun actualOrderingCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("y7OrderingCost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val appHash = hex(MessageDigest.getInstance("SHA-256").digest(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes()))
        check(appHash == args.getString("y7ExpectedApkHash"))
        val testHash = hex(MessageDigest.getInstance("SHA-256").digest(File(instrumentation.context.applicationInfo.sourceDir).readBytes()))
        val source = ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> error("no reader calls") })
        val method = ViewportTrackDataSource::class.java.getDeclaredMethod("orderPoints", List::class.java).apply { isAccessible = true }
        val counts = (args.getString("y7SizeOrder") ?: "16384,65536").split(',').map(String::toInt)
        check(counts.sorted() == listOf(16384, 65536))
        for (count in counts) {
            val sessions = count / 1024
            val rows = ArrayList<ViewportTrackPoint>()
            repeat(count) { i ->
                val session = (i % sessions + 1).toLong()
                val row = ViewportTrackPoint(i + 1L, session, 1000 + session, 0, (i / sessions).toLong(),
                    25.0 + (i % 1024) * 0.00001, 121.0 + (i / 1024) * 0.00001)
                rows += row
                if (i % 8 == 0) rows += row.copy(longitude = row.longitude + 0.000001)
            }
            Collections.shuffle(rows, Random(71))
            val inputHash = hashRows(rows)
            val samples = JSONArray()
            var outputHash: String? = null
            repeat(7) { sample ->
                val cpu = Debug.threadCpuTimeNanos()
                val start = System.nanoTime()
                @Suppress("UNCHECKED_CAST")
                val ordered = method.invoke(source, rows) as List<ViewportTrackPoint>
                val elapsed = System.nanoTime() - start
                val cpuElapsed = Debug.threadCpuTimeNanos() - cpu
                check(ordered.size == count)
                val digest = hashRows(ordered)
                if (outputHash == null) outputHash = digest else check(outputHash == digest)
                samples.put(JSONObject().put("sample", sample).put("wallNs", elapsed).put("cpuNs", cpuElapsed))
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Y7_ORDER_COST ${JSONObject()
                .put("count", count).put("inputRows", rows.size).put("inputSha256", inputHash).put("outputSha256", outputHash)
                .put("appSha256", appHash).put("testSha256", testHash).put("fingerprint", Build.FINGERPRINT)
                .put("samples", samples)}\n") })
        }
    }

    private fun hashRows(rows: List<ViewportTrackPoint>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(56)
        for (row in rows) {
            buffer.clear()
            buffer.putLong(row.pointId); buffer.putLong(row.sessionId); buffer.putLong(row.segmentId)
            buffer.putLong(row.segmentSequence); buffer.putLong(row.pointSequence)
            buffer.putLong(row.latitude.toRawBits()); buffer.putLong(row.longitude.toRawBits())
            digest.update(buffer.array())
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
