package app.trailveil.map.fog

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated bounds work only; includes anchoring, excludes Room, SDK install and cover. */
class GoogleBoundsLayoutCostTest {
    @Test fun composeAndLayoutKeepExactBoundsWithPairedWorkerCosts() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("v2Cost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        for (padding in listOf(1, 2)) for (longitude in listOf(30.0, 179.999)) {
            val keys = FogViewportTileGrid.around(GeoPoint(25.0, longitude), 12, 1, padding)
            val tiles = keys.map { key -> FogMosaicTile(key, FogPixelMask(256, 256, ByteArray(256 * 256) { 184.toByte() })) }
            val expected = FogPocMosaic.compose(tiles).anchoredNear(longitude).bounds
            val samples = JSONArray()
            runBlocking(Dispatchers.Default) {
                check(Looper.myLooper() != Looper.getMainLooper())
                repeat(12) { round ->
                    val order = if (round % 2 == 0) listOf(false, true) else listOf(true, false)
                    for (layout in order) {
                        val cpuStart = Debug.threadCpuTimeNanos()
                        val start = System.nanoTime()
                        val actual = if (layout) FogPocMosaic.layout(tiles).anchoredNear(longitude).bounds
                            else FogPocMosaic.compose(tiles).anchoredNear(longitude).bounds
                        val wall = System.nanoTime() - start
                        val cpu = Debug.threadCpuTimeNanos() - cpuStart
                        check(actual == expected)
                        samples.put(JSONObject().put("round", round).put("layout", layout).put("wallNs", wall).put("cpuNs", cpu))
                    }
                }
            }
            val result = JSONObject().put("case", "p${padding}-" + if (longitude > 100) "dateline" else "ordinary")
                .put("tiles", tiles.size).put("exactBounds", true).put("samples", samples)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "V2_BOUNDS $result\n") })
        }
    }
}
