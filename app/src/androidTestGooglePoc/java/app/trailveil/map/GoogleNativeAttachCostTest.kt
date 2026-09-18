package app.trailveil.map

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.map.fog.*
import com.google.android.gms.maps.GoogleMap
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual installer Main body only; snapshots outside the clocks are progress, not content proof. */
class GoogleNativeAttachCostTest {
    @Test fun nativeAttachMainCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("w1Cost") == "true")
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("w1ExpectedApkHash"))
        val order = (args.getString("w1CaseOrder") ?: "ordinary3,ordinary5,dateline3,dateline5").split(',')
        check(order.sorted() == listOf("dateline3", "dateline5", "ordinary3", "ordinary5"))
        withMap { map ->
            for (name in order) {
                val input = input(if (name.endsWith("3")) 1 else 2, if (name.startsWith("dateline")) 179.999 else 30.0)
                val records = JSONArray()
                repeat(7) { sample ->
                    lateinit var installer: GoogleFogVectorOverlayInstaller
                    instrumentation.runOnMainSync { installer = GoogleFogVectorOverlayInstaller(map) }
                    var wall = 0L; var cpu = 0L
                    try {
                        instrumentation.runOnMainSync {
                            check(Looper.myLooper() == Looper.getMainLooper())
                            val cpuStart = Debug.threadCpuTimeNanos()
                            val start = System.nanoTime()
                            val attached = installer.attachNative(1, input.coverage, input.tiles, input.geometry)
                            wall = System.nanoTime() - start
                            cpu = Debug.threadCpuTimeNanos() - cpuStart
                            check(attached) { installer.describe() }
                            check(installer.covers(1, listOf(input.coverage.center)))
                            check(!installer.covers(1, listOf(GeoPoint(0.0, -90.0))))
                        }
                    } finally {
                        instrumentation.runOnMainSync { check(installer.remove(1)); installer.release() }
                    }
                    snapshotProgress(map)
                    records.put(JSONObject().put("sample", sample).put("wallNs", wall).put("cpuNs", cpu))
                }
                val result = JSONObject().put("case", name).put("caseOrder", order.joinToString(","))
                    .put("appSha256", appHash).put("testSha256", testHash).put("fingerprint", Build.FINGERPRINT)
                    .put("tiles", input.tiles.size).put("inputSha256", inputHash(input))
                    .put("samples", records)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "W1_ATTACH $result\n") })
            }
        }
    }

    @Test fun nativeLayoutKeepsRefusalOrderAndRasterFallback() = withMap { map ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (longitude in listOf(30.0, 179.999, -179.999)) {
            val input = input(1, longitude, width = 8, height = 5)
            instrumentation.runOnMainSync {
                val installer = GoogleFogVectorOverlayInstaller(map)
                try {
                    assertTrue(installer.attachNative(1, input.coverage, input.tiles, input.geometry))
                    assertTrue(installer.covers(1, listOf(input.coverage.center)))
                    assertTrue(installer.remove(1))
                    val patterned = input.tiles.mapIndexed { index, tile ->
                        tile.copy(mask = FogPixelMask(8, 5, ByteArray(40) { if (index == 4) 0 else 184.toByte() }))
                    }
                    val contour = FogMaskContours.decompose(FogPocMosaic.compose(patterned).mask, step = 1)
                    assertTrue(contour.rects.isNotEmpty())
                    assertTrue(installer.attach(2, input.coverage, patterned))
                    assertTrue(installer.describe().contains("rings=${contour.rects.size}"))
                    assertTrue(installer.describe().contains("truncated=${contour.truncated}"))
                    assertTrue(installer.remove(2))
                    assertFalse(installer.attachNative(3, input.coverage, emptyList(), input.geometry))
                    assertTrue(installer.describe().contains("noTiles"))
                    val mismatch = input.geometry.copy(bounds = input.geometry.bounds.copy(northLatitude = input.geometry.bounds.northLatitude + 0.01))
                    assertFalse(installer.attachNative(4, input.coverage, input.tiles, mismatch))
                    assertTrue(installer.describe().contains("nativeBoundsMismatch"))
                    val invalid = input.tiles.toMutableList().apply { this[0] = this[0].copy(mask = FogPixelMask(1, 1, byteArrayOf(1))) }
                    assertFalse(installer.attachNative(5, input.coverage, invalid, input.geometry))
                    assertTrue(installer.describe().contains("composeFailed"))
                    for (bad in listOf(input.tiles.dropLast(1), input.tiles.reversed(),
                        input.tiles.mapIndexed { index, tile -> if (index == 0) tile.copy(key = tile.key.copy(renderVersion = 2)) else tile },
                        input.tiles.mapIndexed { index, tile -> if (index == 0) tile.copy(key = tile.key.copy(zoom = 13)) else tile })) {
                        assertFalse(installer.attachNative(5, input.coverage, bad, input.geometry))
                        assertTrue(installer.describe().contains("composeFailed"))
                    }
                    val short = input.tiles.toMutableList().apply { this[0] = this[0].copy(mask = FogPixelMask(8, 5, ByteArray(39))) }
                    assertThrows(IndexOutOfBoundsException::class.java) { installer.attachNative(6, input.coverage, short, input.geometry) }
                    val long = input.tiles.map { it.copy(mask = FogPixelMask(8, 5, ByteArray(41) { 184.toByte() })) }
                    assertTrue(installer.attachNative(6, input.coverage, long, input.geometry))
                    assertTrue(installer.remove(6))
                    val wide = FogMosaicTile(FogTileKey(0, 0, 0, 1), FogPixelMask(1, 1, byteArrayOf(1)))
                    assertFalse(installer.attachNative(7, input.coverage, listOf(wide), mismatch))
                    assertTrue(installer.describe().contains("imageTooWide"))
                } finally { installer.release() }
            }
            snapshotProgress(map)
        }
    }

    @Test fun presentationBackdropsAndExtentMatchPixelBaseline() {
        var cases = 0
        for (latitude in listOf(-85.0, 0.0, 85.0)) for (longitude in listOf(-179.999, 30.0, 179.999)) {
            for (zoom in listOf(0, 1, 2, 12, 22)) for (padding in listOf(1, 2)) {
                val keys = FogViewportTileGrid.around(GeoPoint(latitude, longitude), zoom, 1, padding)
                val tiles = keys.map { FogMosaicTile(it, FogPixelMask(8, 5, ByteArray(40) { 184.toByte() })) }
                val mosaic = FogPocMosaic.compose(tiles).anchoredNear(longitude)
                val layout = FogPocMosaic.layout(tiles).anchoredNear(longitude)
                val native = FogNativePresentation(FogNativeGeometry(layout.bounds, emptyList(), "test"), layout)
                fun bits(bounds: FogTileBounds) = listOf(bounds.westLongitude, bounds.southLatitude, bounds.eastLongitude, bounds.northLatitude).map { it.toRawBits() }
                assertEquals(bits(mosaic.bounds), bits(native.bounds))
                assertEquals(GoogleFogPolygonGeometry.backdropRectangles(mosaic).map(::bits), GoogleFogPolygonGeometry.backdropRectangles(native).map(::bits))
                assertEquals(FogBackdropGeometry.extent(mosaic), FogBackdropGeometry.extent(native))
                cases++
            }
        }
        assertEquals(90, cases)
    }

    private data class Input(val coverage: FogViewportCoverageRequest, val tiles: List<FogMosaicTile>, val geometry: FogNativeGeometry)
    private fun input(padding: Int, longitude: Double, width: Int = 256, height: Int = width): Input {
        val center = GeoPoint(25.0, longitude)
        val keys = FogViewportTileGrid.around(center, 12, 1, padding)
        val tiles = keys.map { FogMosaicTile(it, FogPixelMask(width, height, ByteArray(width * height) { 184.toByte() })) }
        val bounds = FogPocMosaic.compose(tiles).anchoredNear(longitude).bounds
        val corners = listOf(GeoPoint(bounds.southLatitude, bounds.westLongitude), GeoPoint(bounds.northLatitude, bounds.westLongitude),
            GeoPoint(bounds.northLatitude, bounds.eastLongitude), GeoPoint(bounds.southLatitude, bounds.eastLongitude))
        return Input(FogViewportCoverageRequest(center, 12, corners[0], corners[1], corners[2], corners[3]), tiles,
            FogNativeGeometry(bounds, listOf(FogNativePolygon(corners + corners.first(), emptyList())), "w1-fixture"))
    }
    private fun inputHash(input: Input): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            val bounds = input.geometry.bounds
            listOf(bounds.westLongitude, bounds.southLatitude, bounds.eastLongitude, bounds.northLatitude).forEach { out.writeLong(it.toRawBits()) }
            input.tiles.forEach { tile ->
                out.writeInt(tile.key.zoom); out.writeInt(tile.key.x); out.writeInt(tile.key.y); out.writeInt(tile.key.renderVersion)
                out.writeInt(tile.mask.width); out.writeInt(tile.mask.height); out.write(tile.mask.copyAlpha())
            }
            input.geometry.polygons.forEach { polygon -> polygon.shell.forEach { point -> out.writeLong(point.latitude.toRawBits()); out.writeLong(point.longitude.toRawBits()) } }
        }
        return hash(bytes.toByteArray())
    }
    private fun withMap(block: (GoogleMap) -> Unit) {
        // Emulator-only fixture. This drives a pixel oracle written against the emulator renderer,
        // so on real hardware it cannot mean anything - but it used to say so with a bare `check`,
        // which is a hard failure with no message, and on the owner's phone it failed for that
        // reason alone in every unfiltered run. An assumption is what was meant: sibling probes
        // already guard the identical predicate this way (FogRendererCostProbeTest:23,
        // FogViewportCostProbeTest:30), and the cost probes that DO use `check` only reach it
        // behind an opt-in `assumeTrue`, which is why they never fired on the phone.
        assumeTrue(
            "emulator-only fixture: needs ranchu/goldfish, ran on Build.HARDWARE=" + Build.HARDWARE,
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish",
        )
        val ready = CountDownLatch(1)
        val map = AtomicReference<GoogleMap>()
        GoogleMapSurfaceTestHooks.reset()
        GoogleMapSurfaceTestHooks.fogRequired = false
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.onMapReady.set { map.set(it); ready.countDown() }
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
                check(ready.await(20, TimeUnit.SECONDS))
                snapshotProgress(map.get())
                block(map.get())
            }
        } finally { GoogleMapSurfaceTestHooks.reset() }
    }
    private fun snapshotProgress(map: GoogleMap) {
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            map.snapshot { bitmap -> bitmap?.recycle(); done.countDown() }
        }
        check(done.await(15, TimeUnit.SECONDS)) { "SDK snapshot progress timeout" }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
