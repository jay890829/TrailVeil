package app.trailveil.map

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.data.map.*
import app.trailveil.map.fog.*
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Polygon
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assume.assumeTrue
import kotlin.math.*

internal object GoogleNativeCoordinateTestSupport {
    data class Input(val coverage: FogViewportCoverageRequest, val tiles: List<FogMosaicTile>, val geometry: FogNativeGeometry)
    fun input(name: String = "small"): Input {
        val center = GeoPoint(25.0, 30.0)
        val tiles = FogViewportTileGrid.around(center, 12, 1, 1).map {
            FogMosaicTile(it, FogPixelMask(8, 5, ByteArray(40) { 184.toByte() }))
        }
        val bounds = FogPocMosaic.layout(tiles).anchoredNear(center.longitude).bounds
        fun point(x: Double, y: Double) = GeoPoint(bounds.southLatitude + y * (bounds.northLatitude - bounds.southLatitude), bounds.westLongitude + x * (bounds.eastLongitude - bounds.westLongitude))
        fun rectangle(x: Double, y: Double, size: Double) = listOf(point(x,y), point(x,y+size), point(x+size,y+size), point(x+size,y), point(x,y))
        val shell = when (name) {
            "vertices1000", "vertices12000" -> {
                val n = if (name == "vertices1000") 1000 else 12000
                val ring = (0 until n).map { point(0.5 + 0.45*cos(it*2*PI/n), 0.5+0.45*sin(it*2*PI/n)) }
                ring + ring.first()
            }
            else -> rectangle(0.0, 0.0, 1.0)
        }
        val holes = if (name == "holes2400") (0 until 2400).map { rectangle(0.05 + (it%48)*0.018, 0.05+(it/48)*0.018, 0.01).reversed() } else emptyList()
        val corners = rectangle(0.0,0.0,1.0)
        return Input(FogViewportCoverageRequest(center, 12, corners[0], corners[1], corners[2], corners[3]), tiles,
            FogNativeGeometry(bounds, listOf(FogNativePolygon(shell, holes)), "x1-fixture"))
    }
    fun track(map: GoogleMap, geometry: () -> FogNativeGeometry?): GoogleTrackFogOverlayInstaller {
        val engine = object : FogNativeGeometryEngine {
            override val description = "x1-fixture"
            override suspend fun render(bounds: FogTileBounds, style: FogRenderStyle, read: suspend (ViewportBounds) -> List<TrackSegment>) = geometry()
            override fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle) = Unit
            override fun clear() = Unit
        }
        val feed = object : PersistedTrackPointChangeFeed {
            override suspend fun latestCursor() = PersistedPointCursor(0)
            override fun revisionsAfter(cursor: PersistedPointCursor) = emptyFlow<PersistedPointRevision>()
            override suspend fun readChangesAfter(cursor: PersistedPointCursor, limit: Int) = emptyList<PersistedTrackPointChange>()
        }
        val coordinator = FogViewportCoordinator(ViewportTrackDataSource { _, _, _ -> error("unexpected Room read") },
            FogTilePipeline(FogMemoryTileCache(1024 * 1024L), null, FogTileRenderer()::render), nativeGeometryEngine = engine)
        return GoogleTrackFogOverlayInstaller(GoogleFogSurfaceContext(map, FogRuntime(coordinator,feed), {}, {}, { throw it }, null, {}, { emptyList() }, { false }, {}, null, { true }))
    }
    fun inputHash(input: Input): String = digest { out ->
        val b = input.geometry.bounds
        listOf(b.westLongitude,b.southLatitude,b.eastLongitude,b.northLatitude).forEach { out.writeLong(it.toRawBits()) }
        input.tiles.forEach { tile ->
            listOf(tile.key.zoom,tile.key.x,tile.key.y,tile.key.renderVersion,tile.mask.width,tile.mask.height).forEach(out::writeInt)
            out.write(tile.mask.copyAlpha())
        }
        out.writeInt(input.geometry.polygons.size)
        input.geometry.polygons.forEach { p ->
            out.writeInt(1+p.holes.size)
            (listOf(p.shell)+p.holes).forEach { ring ->
                out.writeInt(ring.size)
                ring.forEach { out.writeLong(it.latitude.toRawBits()); out.writeLong(it.longitude.toRawBits()) }
            }
        }
    }
    fun sdkHash(owner: Any, generation: Long): String {
        fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
        val vector = if (owner is GoogleTrackFogOverlayInstaller) field(owner,"vector")!! else owner
        val installed = (field(vector,"installed") as Map<*,*>)[generation] ?: error("missing SDK generation")
        @Suppress("UNCHECKED_CAST") val polygons = field(installed,"polygons") as List<Polygon>
        return digest { out ->
            out.writeInt(polygons.size)
            polygons.forEach { p ->
                out.writeInt(p.fillColor); out.writeInt(p.strokeColor); out.writeInt(p.strokeWidth.toRawBits()); out.writeInt(p.zIndex.toRawBits())
                out.writeBoolean(p.isVisible); out.writeBoolean(p.isGeodesic); out.writeBoolean(p.isClickable)
                val rings = listOf(p.points) + p.holes
                out.writeInt(rings.size)
                rings.forEach { ring -> out.writeInt(ring.size); ring.forEach { out.writeLong(it.latitude.toRawBits()); out.writeLong(it.longitude.toRawBits()) } }
            }
        }
    }
    private fun digest(block: (DataOutputStream) -> Unit): String {
        val bytes = ByteArrayOutputStream(); DataOutputStream(bytes).use(block); return hash(bytes.toByteArray())
    }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    fun withMap(block: (GoogleMap) -> Unit) {
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
        val ready = CountDownLatch(1); val map = AtomicReference<GoogleMap>()
        GoogleMapSurfaceTestHooks.reset(); GoogleMapSurfaceTestHooks.fogRequired = false
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true,null))
        GoogleMapSurfaceTestHooks.onMapReady.set { map.set(it); ready.countDown() }
        try { ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
            check(ready.await(20,TimeUnit.SECONDS)); snapshotProgress(map.get()); block(map.get())
        } } finally { GoogleMapSurfaceTestHooks.reset() }
    }
    fun snapshotProgress(map: GoogleMap) {
        val done=CountDownLatch(1); main { map.snapshot { bitmap -> bitmap?.recycle(); done.countDown() } }
        check(done.await(15,TimeUnit.SECONDS)) { "SDK snapshot progress timeout" }
    }
}
