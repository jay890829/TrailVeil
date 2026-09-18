package app.trailveil.map

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPointReader
import app.trailveil.map.fog.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Identical test APK calls the real placeholder method in b54 and the candidate. */
class MapLibrePlaceholderCostTest {
    @Test fun exactPlaceholderWorkerCost() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("w2Cost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("w2ExpectedApkHash"))
        val order = (args.getString("w2CaseOrder") ?: "world,low,ordinary,dateline").split(',')
        check(order.sorted() == listOf("dateline", "low", "ordinary", "world"))
        val requests = mapOf(
            "world" to FogViewportRequest(GeoPoint(0.0, 0.0), 0.0),
            "low" to FogViewportRequest(GeoPoint(0.0, 0.0), 1.75),
            "ordinary" to FogViewportRequest(GeoPoint(25.0, 121.0), 12.75),
            "dateline" to FogViewportRequest(GeoPoint(85.0, -179.99), 12.75),
        )
        for (name in order) {
            val request = requests.getValue(name)
            val style = FogRenderStyle()
            val pipeline = FogTilePipeline(FogMemoryTileCache(4L * 1024 * 1024), null) { _, _ -> error("pipeline touched") }
            val coordinator = FogViewportCoordinator(
                ViewportTrackDataSource(ViewportTrackPointReader { _, _, _ -> error("canonical data touched") }),
                pipeline, style, mosaicPaddingTiles = 2,
            )
            val keys = FogViewportTileGrid.around(request.center, request.mapZoom.toInt(), FogRenderVersions.CURRENT)
            val renderer = FogTileRenderer(style)
            val expected = FogPocMosaic.compose(keys.map { FogMosaicTile(it, renderer.render(it, emptyList())) })
                .anchoredNear(request.center.longitude)
            val expectedHash = mosaicHash(expected)
            val samples = JSONArray()
            repeat(7) { sample ->
                val result = runBlocking(Dispatchers.Default) {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    val cpu = Debug.threadCpuTimeNanos()
                    val start = System.nanoTime()
                    val actual = coordinator.placeholder(request)
                    val wall = System.nanoTime() - start
                    val cpuTime = Debug.threadCpuTimeNanos() - cpu
                    Triple(actual, wall, cpuTime)
                }
                val actual = result.first
                assertEquals(keys, actual.keys)
                assertNull(actual.queryBounds)
                assertNull(actual.nativeGeometryStatus)
                assertSame(request, actual.request)
                assertEquals(expected, actual.presentation)
                assertEquals(expectedHash, mosaicHash(actual.presentation as FogTileMosaic))
                assertTrue(pipeline.cachedKeys().isEmpty())
                samples.put(JSONObject().put("sample", sample).put("wallNs", result.second).put("cpuNs", result.third))
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "W2_PLACEHOLDER " + JSONObject().put("case", name).put("appSha256", appHash)
                    .put("testSha256", testHash).put("outputSha256", expectedHash).put("tileCount", keys.size)
                    .put("samples", samples).toString() + "\n")
            })
        }
    }

    private fun mosaicHash(mosaic: FogTileMosaic): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(44).putInt(mosaic.mask.width).putInt(mosaic.mask.height).putInt(mosaic.tileCount)
            .putLong(mosaic.bounds.westLongitude.toRawBits()).putLong(mosaic.bounds.eastLongitude.toRawBits())
            .putLong(mosaic.bounds.northLatitude.toRawBits()).putLong(mosaic.bounds.southLatitude.toRawBits()).array())
        digest.update(mosaic.mask.copyAlpha())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
