package app.trailveil.googlepoc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.TextureView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.map.fog.FogRenderStyle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-012` design 2 precondition spike. Design 2 attaches every fog overlay fully transparent
 * (transparency 1.0) so the SDK renders it before it is shown, then reveals it by one transparency
 * change. That only works if the SDK still requests and rasterises tiles for an overlay it does not
 * draw, and if a later transparency change is applied to tiles it already holds. This spike adds a
 * raw `TileOverlay` above the app fog with a counting provider of opaque magenta tiles, in several
 * scenarios: attached visible (the capture/classifier control), attached at a starting transparency
 * (0, 0.5, 254/255, 1.0) and then flipped to the shared fog opacity, with a one-pixel camera nudge
 * and then `clearTileCache` as escalations when the flip alone shows nothing. Frames after each step
 * are saved as PNGs for the host to pull.
 *
 * Opt-in: `trailveilGoogleFogHiddenPreRender=true`. Evidence lines carry no coordinates.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogHiddenOverlayPreRenderSpikeTest {

    private class CountingMagentaProvider : TileProvider {
        val requests = AtomicInteger(0)
        val firstRequestNanos = AtomicLong(0L)
        val lastRequestNanos = AtomicLong(0L)
        private val png: ByteArray = run {
            val bitmap = createBitmap(TILE_PX, TILE_PX)
            bitmap.eraseColor(Color.MAGENTA)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }

        override fun getTile(x: Int, y: Int, zoom: Int): Tile {
            val now = SystemClock.elapsedRealtimeNanos()
            firstRequestNanos.compareAndSet(0L, now)
            lastRequestNanos.set(now)
            requests.incrementAndGet()
            return Tile(TILE_PX, TILE_PX, png)
        }
    }

    private data class Scenario(val label: String, val startTransparency: Float, val flipAtDelivery: Boolean = false)

    private lateinit var context: android.content.Context
    private lateinit var activity: GoogleMapsPocActivity
    private lateinit var mapView: MapView
    private lateinit var map: GoogleMap
    private var exclusions: List<Rect> = emptyList()
    private val fileName = "hidden-prerender.txt"

    private fun emit(line: String) = SpikeEvidence.emit(context, fileName, line)

    @Test
    fun hiddenTileOverlayIsRenderedBeforeItIsRevealed() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilGoogleFogHiddenPreRender")
        SpikeScenarioSupport.assumeKeyConfigured()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = GoogleRendererPin.initialize(context, "latest")

        val installLatch = CountDownLatch(1)
        val scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCanonicalFogInstalled(generation: Long) = installLatch.countDown()
                }
            }
            mapView = SpikeScenarioSupport.awaitMapView(scenario)
            map = SpikeScenarioSupport.awaitGoogleMap(scenario, mapView)
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            scenario.onActivity { it.setStatusOverlaySuppressedForTesting(true) }
            activity = SpikeScenarioSupport.requireActivity(scenario)
            val fogInstalled = installLatch.await(30, TimeUnit.SECONDS)
            SystemClock.sleep(2_000L)
            val (rects, exclusionFallback) = SpikeCaptureSupport.liveExclusionRects(mapView)
            exclusions = rects
            emit(
                "renderer=$renderer appFogInstalled=$fogInstalled map=${mapView.width}x${mapView.height} " +
                    "exclusionFallback=$exclusionFallback visibleTransparency=$VISIBLE_TRANSPARENCY",
            )
            val baseline = capture("baseline")
            emit("baseline magenta=${baseline.magenta} fogFamily=${baseline.fogFamily} method=${baseline.method}")
            // The PoC activity's own fog overlay is opaque at the maximum z-index; nothing can draw
            // above it, so it is hidden for the spike (the spike overlay then sits over the basemap).
            val pocFog = AtomicReference<TileOverlay?>()
            onMain {
                pocFog.set(activity.fogOverlayControllerForTesting()?.attach())
                pocFog.get()?.isVisible = false
            }
            SystemClock.sleep(1_000L)
            val uncovered = capture("uncovered")
            emit("pocFogHidden=${pocFog.get() != null} uncovered magenta=${uncovered.magenta} fogFamily=${uncovered.fogFamily}")

            val outcomes = listOf(
                Scenario("visibleFromStart", VISIBLE_TRANSPARENCY),
                Scenario("from1.0", 1.0F),
                Scenario("from1.0-atDelivery", 1.0F, flipAtDelivery = true),
                Scenario("from1.0-atDelivery-2", 1.0F, flipAtDelivery = true),
                Scenario("from254of255-atDelivery", 254F / 255F, flipAtDelivery = true),
            ).map(::runScenario)
            outcomes.forEach(::emit)
            // Leave the evidence files in place long enough for the host to pull them.
            SystemClock.sleep(PULL_GRACE_MILLIS)
            assertTrue(
                "the control scenario must show magenta: ${outcomes.first()}",
                outcomes.first().contains("afterFlip=revealed"),
            )
        } finally {
            scenario.close()
        }
    }

    /** Attach at the scenario's transparency, wait, flip to visible, escalate; returns a summary. */
    private fun runScenario(scenario: Scenario): String {
        val provider = CountingMagentaProvider()
        val overlay = AtomicReference<TileOverlay?>()
        val attachNanos = AtomicLong(0L)
        onMain {
            attachNanos.set(SystemClock.elapsedRealtimeNanos())
            overlay.set(
                map.addTileOverlay(
                    TileOverlayOptions()
                        .tileProvider(provider)
                        .fadeIn(false)
                        .transparency(scenario.startTransparency)
                        .zIndex(SPIKE_Z),
                ),
            )
        }
        val attached = overlay.get() ?: return "${scenario.label}: addTileOverlay returned null"
        try {
            if (scenario.flipAtDelivery) {
                // Production timing: the reveal follows the last tile delivery. Wait for the first
                // request, then for a 100 ms lull in requests, and flip at once.
                val deadline = SystemClock.elapsedRealtimeNanos() + SETTLE_MILLIS * NANOS_PER_MILLI
                while (SystemClock.elapsedRealtimeNanos() < deadline) {
                    val last = provider.lastRequestNanos.get()
                    if (last != 0L && SystemClock.elapsedRealtimeNanos() - last > DELIVERY_LULL_MILLIS * NANOS_PER_MILLI) break
                    SystemClock.sleep(5L)
                }
            } else {
                SystemClock.sleep(SETTLE_MILLIS)
            }
            val startRequests = provider.requests.get()
            val firstRequestMs = provider.firstRequestNanos.get().let { first ->
                if (first == 0L) -1L else (first - attachNanos.get()) / NANOS_PER_MILLI
            }
            val sinceLastRequestMs = provider.lastRequestNanos.get().let { last ->
                if (last == 0L) -1L else (SystemClock.elapsedRealtimeNanos() - last) / NANOS_PER_MILLI
            }
            val beforeFlip = if (scenario.flipAtDelivery) Frame(-1.0, -1.0, "skipped", null) else capture("${scenario.label}-beforeFlip")
            emit(
                "${scenario.label} attached: requests=$startRequests firstRequestMs=$firstRequestMs " +
                    "sinceLastRequestMs=$sinceLastRequestMs magenta=${beforeFlip.magenta} fogFamily=${beforeFlip.fogFamily}",
            )

            val afterFlip = step(scenario.label, "afterFlip", provider) {
                attached.transparency = VISIBLE_TRANSPARENCY
            }
            var afterInvalidate = "skipped"
            var afterZeroScroll = "skipped"
            var afterNudge = "skipped"
            var afterClear = "skipped"
            var revealed = afterFlip.startsWith("revealed")
            if (!revealed) {
                afterInvalidate = step(scenario.label, "afterInvalidate", provider) { mapView.invalidate() }
                revealed = afterInvalidate.startsWith("revealed")
            }
            if (!revealed) {
                afterZeroScroll = step(scenario.label, "afterZeroScroll", provider) {
                    map.moveCamera(CameraUpdateFactory.scrollBy(0F, 0F))
                }
                revealed = afterZeroScroll.startsWith("revealed")
            }
            if (!revealed) {
                afterNudge = step(scenario.label, "afterNudge", provider) {
                    map.moveCamera(CameraUpdateFactory.scrollBy(1F, 1F))
                }
                revealed = afterNudge.startsWith("revealed")
            }
            if (!revealed) {
                afterClear = step(scenario.label, "afterClear", provider) { attached.clearTileCache() }
            }
            return "${scenario.label}: requestsWhileAttached=$startRequests afterFlip=$afterFlip " +
                "afterInvalidate=$afterInvalidate afterZeroScroll=$afterZeroScroll afterNudge=$afterNudge afterClear=$afterClear"
        } finally {
            onMain { attached.remove() }
            SystemClock.sleep(1_000L)
        }
    }

    /** Runs one main-thread action, then watches frames; returns "revealed@<ms>..." or "none...". */
    private fun step(label: String, name: String, provider: CountingMagentaProvider, action: () -> Unit): String {
        val requestsBefore = provider.requests.get()
        val startNanos = AtomicLong(0L)
        onMain {
            action()
            startNanos.set(SystemClock.elapsedRealtimeNanos())
        }
        var frames = 0
        var firstFrameMs = -1L
        var firstMagenta = -1.0
        var lastMagenta = -1.0
        var revealedMs = -1L
        val trace = StringBuilder()
        val deadline = startNanos.get() + WATCH_MILLIS * NANOS_PER_MILLI
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            val captureStartMs = (SystemClock.elapsedRealtimeNanos() - startNanos.get()) / NANOS_PER_MILLI
            val frame = capture(null)
            val captureEndMs = (SystemClock.elapsedRealtimeNanos() - startNanos.get()) / NANOS_PER_MILLI
            frames += 1
            if (frames <= TRACE_FRAMES) trace.append("${captureStartMs}-${captureEndMs}ms:${"%.3f".format(frame.magenta)} ")
            if (firstFrameMs < 0) {
                firstFrameMs = captureStartMs
                firstMagenta = frame.magenta
            }
            lastMagenta = frame.magenta
            if (frame.magenta >= REVEALED_FRACTION) {
                revealedMs = captureStartMs
                break
            }
        }
        val saved = capture("$label-$name")
        val requests = provider.requests.get() - requestsBefore
        val verdict = if (revealedMs >= 0) "revealed@${revealedMs}ms" else "none"
        emit(
            "$label $name: $verdict frames=$frames firstFrameMs=$firstFrameMs firstMagenta=$firstMagenta " +
                "lastMagenta=$lastMagenta savedMagenta=${saved.magenta} savedFogFamily=${saved.fogFamily} " +
                "requestsDuringStep=$requests method=${saved.method} png=${saved.png} trace=[${trace.toString().trim()}]",
        )
        return "$verdict(requests=$requests,final=${"%.3f".format(saved.magenta)})"
    }

    private data class Frame(val magenta: Double, val fogFamily: Double, val method: String, val png: String?)

    /**
     * Fractions of sampled, non-excluded map pixels reading as magenta at fog opacity / as fog.
     * The fast path reads the map's TextureView back at a quarter of its size (a full-size readback
     * costs about 190 ms on this emulator, too slow to time a reveal); the slow path is the shared
     * screen-truth capture.
     */
    private fun capture(saveAs: String?): Frame {
        val surface = with(SpikeScenarioSupport) { mapView.findRenderSurface() }
        val fast = (surface as? TextureView)?.let { texture ->
            try {
                val small = createBitmap(texture.width / DOWNSCALE, texture.height / DOWNSCALE)
                texture.getBitmap(small)
                SpikeScenarioSupport.CaptureResult(small, "TEXTURE_VIEW_QUARTER")
            } catch (_: Exception) {
                null
            }
        }
        val result = fast
            ?: SpikeScenarioSupport.captureMapView(activity, mapView)
            ?: SpikeScenarioSupport.captureScreenTruth(mapView)
            ?: return Frame(-1.0, -1.0, "none", null)
        val bitmap = result.bitmap
        val scale = if (fast != null) DOWNSCALE else 1
        val stride = if (fast != null) 1 else STRIDE_PX
        var analysed = 0
        var magenta = 0
        var fog = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (exclusions.none { it.contains(x * scale, y * scale) }) {
                    analysed += 1
                    val pixel = bitmap[x, y]
                    if (isMagentaAtFogOpacity(pixel)) magenta += 1
                    if (SpikeCaptureSupport.isFogFamily(pixel)) fog += 1
                }
                x += stride
            }
            y += stride
        }
        val png = saveAs?.let { name ->
            val scaled = if (fast != null) bitmap else bitmap.scale(bitmap.width / 4, bitmap.height / 4)
            SpikeEvidence.savePng(context, scaled, "prerender-$name.png").also { if (scaled !== bitmap) scaled.recycle() }
        }
        bitmap.recycle()
        val denominator = analysed.toDouble().coerceAtLeast(1.0)
        return Frame(magenta / denominator, fog / denominator, result.method, png)
    }

    /**
     * Magenta at alpha 184/255 over anything: red and blue at least 0.72 * 255 = 184 (minus
     * tolerance), green at most 0.28 * 255 = 71 (plus tolerance). Fog, basemap and labels all fail.
     */
    private fun isMagentaAtFogOpacity(pixel: Int): Boolean =
        Color.red(pixel) >= 170 && Color.blue(pixel) >= 170 && Color.green(pixel) <= 90

    private fun onMain(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                block()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
    }

    private companion object {
        const val TILE_PX = 256
        const val SPIKE_Z = 1_000F
        const val SETTLE_MILLIS = 3_000L
        const val DELIVERY_LULL_MILLIS = 100L
        const val WATCH_MILLIS = 3_000L
        const val TRACE_FRAMES = 40
        const val PULL_GRACE_MILLIS = 25_000L
        /** The visible control reads about 0.88: labels and roads draw above tile overlays. */
        const val REVEALED_FRACTION = 0.80
        const val STRIDE_PX = 4
        const val DOWNSCALE = 4
        const val NANOS_PER_MILLI = 1_000_000L
        val VISIBLE_TRANSPARENCY: Float = 1F - FogRenderStyle().fogAlpha / 255F
    }
}
