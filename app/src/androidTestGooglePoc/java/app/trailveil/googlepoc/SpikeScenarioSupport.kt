package app.trailveil.googlepoc

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.data.db.TrailVeilDatabase
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * `V02-005` stage 3: the scale benchmark's driving idioms, shared by every spike test. The
 * benchmark file itself is accepted V02-004 evidence and stays untouched; these are the same
 * shapes with the same diagnostic error bodies.
 */
object SpikeScenarioSupport {
    const val FALLBACK_TAG = "trailveil_google_poc_fallback"
    const val SURFACE_POLL_COUNT = 300
    const val SURFACE_POLL_MILLIS = 100L
    const val MAP_VIEW_POLL_COUNT = 100
    const val MAP_VIEW_POLL_MILLIS = 100L
    const val MAP_READY_TIMEOUT_SECONDS = 30L
    const val NANOS_PER_MILLISECOND = 1_000_000L

    val CANONICAL_TABLES = listOf(
        "recording_sessions",
        "track_segments",
        "track_points",
        "track_point_cells",
        "recording_operation_receipts",
        "recording_location_receipt_windows",
        "recording_location_receipt_retention_states",
    )

    data class SurfaceObservation(
        val fallbackVisibility: Int?,
        val mapView: MapView?,
        val mapViewPresent: Boolean,
    )

    fun assumeSpikeArgument(argument: String) {
        assumeTrue(
            "spike is opt-in; pass -Pandroid.testInstrumentationRunnerArguments.$argument=true",
            InstrumentationRegistry.getArguments().getString(argument) == "true",
        )
    }

    fun assumeKeyConfigured() {
        assumeTrue(
            "Google PoC runtime key is not configured; host builds remain compile-only",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
    }

    fun assumeEmptyCanonicalTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = TrailVeilDatabase.open(context)
        try {
            val sqlite = database.openHelper.readableDatabase
            val counts = CANONICAL_TABLES.associateWith { table ->
                sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
            }
            assumeTrue(
                "spike needs a dedicated empty app install",
                counts.values.all { count -> count == 0L },
            )
        } finally {
            database.close()
        }
    }

    fun elapsedMillisSince(startNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / NANOS_PER_MILLISECOND

    fun readSurface(scenario: ActivityScenario<GoogleMapsPocActivity>): SurfaceObservation {
        val result = AtomicReference<SurfaceObservation?>()
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val fallback = decor.findTaggedView(FALLBACK_TAG)
            val mapView = decor.findGoogleMapView()
            result.set(
                SurfaceObservation(
                    fallbackVisibility = fallback?.visibility,
                    mapView = mapView,
                    mapViewPresent = mapView != null,
                ),
            )
        }
        return requireNotNull(result.get())
    }

    fun requireActivity(scenario: ActivityScenario<GoogleMapsPocActivity>): GoogleMapsPocActivity {
        val activity = AtomicReference<GoogleMapsPocActivity?>()
        scenario.onActivity { activity.set(it) }
        return requireNotNull(activity.get())
    }

    fun awaitMapView(scenario: ActivityScenario<GoogleMapsPocActivity>): MapView {
        repeat(MAP_VIEW_POLL_COUNT) {
            val surface = readSurface(scenario)
            if (surface.mapView != null) return requireNotNull(surface.mapView)
            SystemClock.sleep(MAP_VIEW_POLL_MILLIS)
        }
        error("Google PoC MapView was not attached")
    }

    fun awaitGoogleMap(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        mapView: MapView,
    ): GoogleMap {
        val ready = CountDownLatch(1)
        val map = AtomicReference<GoogleMap?>()
        scenario.onActivity {
            mapView.getMapAsync {
                map.set(it)
                ready.countDown()
            }
        }
        assertTrue(
            "Google PoC map did not become ready",
            ready.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return requireNotNull(map.get())
    }

    fun awaitFallbackGone(scenario: ActivityScenario<GoogleMapsPocActivity>) {
        repeat(SURFACE_POLL_COUNT) {
            val surface = readSurface(scenario)
            if (surface.fallbackVisibility == View.GONE) return
            SystemClock.sleep(SURFACE_POLL_MILLIS)
        }
        val diagnostic = AtomicReference<GoogleFogInstallDiagnostic>()
        scenario.onActivity { activity ->
            diagnostic.set(activity.fogInstallDiagnosticForTesting())
        }
        val state = requireNotNull(diagnostic.get())
        error(
            "Google PoC fallback did not hide after canonical fog readiness " +
                "phase=${state.phase} pendingTiles=${state.pendingTileCount} " +
                "refreshFailure=${state.refreshFailure} " +
                "clearFailureClass=${state.clearFailureClass} " +
                "refreshGeneration=${state.refreshGeneration} " +
                "refreshStarted=${state.refreshStarted} " +
                "refreshPublished=${state.refreshPublished} " +
                "visualRequiredTiles=${state.visualRequiredTileCount} " +
                "visualVerifiedTiles=${state.visualVerifiedTileCount} " +
                "snapshotAttempt=${state.snapshotAttempt} " +
                "visualOffScreenOnlyTiles=${state.visualOffScreenOnlyTileCount} " +
                "visualMismatchedTiles=${state.visualMismatchedTileCount} " +
                "visualMinimumOnScreenProbes=${state.visualMinimumOnScreenProbeCount}",
        )
    }

    /**
     * Distinguishes the expected per-move canonical cover from a TERMINAL fallback: terminal
     * means the fallback view is stably VISIBLE while the MapView has been disposed. Returns
     * true when the terminal state is confirmed (spikes mark the run INVALID, never FAIL).
     */
    fun isTerminalFallback(scenario: ActivityScenario<GoogleMapsPocActivity>): Boolean {
        var consecutive = 0
        repeat(5) {
            val surface = readSurface(scenario)
            if (surface.fallbackVisibility == View.VISIBLE && !surface.mapViewPresent) {
                consecutive += 1
            } else {
                return false
            }
            SystemClock.sleep(SURFACE_POLL_MILLIS)
        }
        return consecutive == 5
    }

    fun View.findTaggedView(expectedTag: String): View? {
        if (tag == expectedTag) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findTaggedView(expectedTag)?.let { return it }
        }
        return null
    }

    fun View.findGoogleMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findGoogleMapView()?.let { return it }
        }
        return null
    }

    fun View.findRenderSurface(): View? {
        if (this is SurfaceView || this is TextureView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findRenderSurface()?.let { return it }
        }
        return null
    }

    data class CaptureResult(val bitmap: Bitmap, val method: String)

    private val captureThread by lazy {
        HandlerThread("trailveil-spike-capture").also(HandlerThread::start)
    }

    /**
     * The screen-truth oracle. Preference order: PixelCopy against the MapView's own render
     * SurfaceView (the window overload would capture the punched-through hole — banned), then
     * TextureView.getBitmap, then uiAutomation.takeScreenshot cropped to the MapView. Every
     * PixelCopy result is degeneracy-checked (a uniformly transparent/black map region means the
     * copy silently missed the composited surface) before it is trusted.
     */
    fun captureMapView(activity: Activity, mapView: MapView): CaptureResult? {
        val surface = mapView.findRenderSurface()
        if (surface is SurfaceView) {
            val bitmap = createBitmap(surface.width, surface.height)
            val latch = CountDownLatch(1)
            var status = -1
            try {
                PixelCopy.request(
                    surface,
                    bitmap,
                    { result ->
                        status = result
                        latch.countDown()
                    },
                    Handler(captureThread.looper),
                )
                latch.await(5, TimeUnit.SECONDS)
                if (status == PixelCopy.SUCCESS && !isDegenerate(bitmap)) {
                    return CaptureResult(bitmap, "PIXEL_COPY_SURFACE")
                }
            } catch (_: Exception) {
                // Fall through to the next channel.
            }
            bitmap.recycle()
        }
        if (surface is TextureView) {
            try {
                val bitmap = surface.getBitmap(
                    createBitmap(surface.width, surface.height),
                )
                if (bitmap != null && !isDegenerate(bitmap)) {
                    return CaptureResult(bitmap, "TEXTURE_VIEW")
                }
                bitmap?.recycle()
            } catch (_: Exception) {
                // Fall through to the next channel.
            }
        }
        repeat(3) {
            try {
                val screenshot = InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .takeScreenshot()
                if (screenshot != null) {
                    val location = IntArray(2)
                    mapView.getLocationOnScreen(location)
                    val rect = Rect(
                        location[0].coerceAtLeast(0),
                        location[1].coerceAtLeast(0),
                        (location[0] + mapView.width).coerceAtMost(screenshot.width),
                        (location[1] + mapView.height).coerceAtMost(screenshot.height),
                    )
                    if (rect.width() > 0 && rect.height() > 0) {
                        val cropped = Bitmap.createBitmap(
                            screenshot,
                            rect.left,
                            rect.top,
                            rect.width(),
                            rect.height(),
                        )
                        // createBitmap RETURNS THE SOURCE when the crop covers it entirely —
                        // recycling unconditionally would recycle the returned bitmap too.
                        if (cropped !== screenshot) screenshot.recycle()
                        return CaptureResult(cropped, "UI_AUTOMATION")
                    }
                    screenshot.recycle()
                }
            } catch (_: Exception) {
                // Bounded retry below.
            }
            SystemClock.sleep(500L)
        }
        return null
    }

    /**
     * Screen-truth capture: the composited screen INCLUDING sibling views (watermark, compass),
     * cropped to the MapView. Surface readbacks (PixelCopy / TextureView.getBitmap) exclude the
     * view layer — measured on the LEGACY renderer, where the watermark ImageView never appears
     * in a TextureView readback — so visibility-of-views questions must use this channel.
     */
    fun captureScreenTruth(mapView: MapView): CaptureResult? {
        repeat(3) {
            try {
                val screenshot = InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .takeScreenshot()
                if (screenshot != null) {
                    val location = IntArray(2)
                    mapView.getLocationOnScreen(location)
                    val rect = Rect(
                        location[0].coerceAtLeast(0),
                        location[1].coerceAtLeast(0),
                        (location[0] + mapView.width).coerceAtMost(screenshot.width),
                        (location[1] + mapView.height).coerceAtMost(screenshot.height),
                    )
                    if (rect.width() > 0 && rect.height() > 0) {
                        val cropped = Bitmap.createBitmap(
                            screenshot,
                            rect.left,
                            rect.top,
                            rect.width(),
                            rect.height(),
                        )
                        if (cropped !== screenshot) screenshot.recycle()
                        return CaptureResult(cropped, "UI_AUTOMATION")
                    }
                    screenshot.recycle()
                }
            } catch (_: Exception) {
                // Bounded retry below.
            }
            SystemClock.sleep(500L)
        }
        return null
    }

    /** A capture whose sampled pixels are uniformly transparent-or-black never saw the map. */
    private fun isDegenerate(bitmap: Bitmap): Boolean {
        if (bitmap.width < 8 || bitmap.height < 8) return true
        var blackOrTransparent = 0
        var sampled = 0
        var y = bitmap.height / 8
        while (y < bitmap.height) {
            var x = bitmap.width / 8
            while (x < bitmap.width) {
                val pixel = bitmap[x, y]
                sampled += 1
                val opaqueBlack = pixel == -16777216
                if (pixel == 0 || opaqueBlack) blackOrTransparent += 1
                x += bitmap.width / 4
            }
            y += bitmap.height / 4
        }
        return sampled > 0 && blackOrTransparent == sampled
    }
}
