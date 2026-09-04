package app.trailveil.googlepoc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.map.GoogleMapSurfaceTestActivity
import app.trailveil.map.GoogleMapSurfaceTestHooks
import app.trailveil.map.ProviderStartupDecision
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertTrue

/**
 * `V02-007` gesture-exposure screen truth for the real hosted production map.
 *
 * The claim these pieces serve is the Google reading of the MapLibre gesture cases: while a real
 * gesture runs over never-visited ground, every sampled frame is either the opaque
 * `GoogleFogSafetyOverlay` or fog-family pixels, never bare basemap; the blanking the cover causes
 * is bounded ABOVE rather than forbidden (Google raises it on purpose once the camera leaves
 * proven coverage); and the gesture really moved the camera and really reached the map, so a
 * swallowed gesture cannot pass vacuously.
 *
 * Three properties of the Google surface shape every decision below, and each was read out of the
 * production sources rather than assumed.
 *
 * 1. **The cover is a view-layer object.** `GoogleFogSafetyOverlay` adds a drawable to
 *    `MapView.overlay`, so a surface readback (`PixelCopy` on the SDK's own render `SurfaceView`,
 *    or `TextureView.getBitmap`) does NOT contain it - a readback taken while the cover is up shows
 *    the un-proven basemap underneath. Covered frames are therefore never judged as pixels; they
 *    are discharged by a separate screen-truth corroboration through
 *    `uiAutomation.takeScreenshot`, which is the only channel that composites the view layer.
 * 2. **`countNonFog == 0` is not available.** Basemap labels composite ABOVE the fog `TileOverlay`;
 *    the repository's own recorded figure on this emulator image is `noiseFloorPctMax=12.247`
 *    (`V02-005-spikes.md`, SP5 `measure` runs), and `GoogleSettledFogCoverageSweepTest` records the
 *    same ~12% in its header. So the leak oracle is [FlingExposureVideoAnalyzer]'s label-aware rule
 *    - an 8-connected cluster of non-fog samples at or above
 *    [FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX] AND an area above a floor MEASURED at the same
 *    camera, on the same capture channel - never an absolute pixel count.
 * 3. **The floor needs a scale, and the scale is the sensitivity arm.** Every trial's start camera
 *    is replayed on `GoogleMapSurfaceTestActivity` with `fogRequired` false, which is the same
 *    `GoogleHostedMapSurface` with the fog detached. That reading is simultaneously the proof that
 *    the oracle can see bare basemap in THIS run (SP5's falsify arm, made mandatory instead of
 *    opt-in) and the scale the settled floor is bounded against.
 */
internal object GestureExposurePixels {
    /** Every 4th pixel in both axes: ~34k samples of a 1080x2000 map, cheap enough per frame. */
    const val SAMPLE_STRIDE_PX = 4

    /** Compositor edge artefacts on a scaled readback are not a fog verdict. */
    const val EDGE_INSET_PX = 6

    /**
     * The band the entry screen's own controls cannot reach: the menu button and notice column
     * grow down from the top, the recentre button sits at the bottom end, and the SDK watermark
     * and compass are in the bottom-left and top-right corners. Only ever applied to a composited
     * capture; surface readbacks contain no app chrome at all and are judged whole.
     */
    const val BAND_TOP_FRACTION = 0.34
    const val BAND_BOTTOM_FRACTION = 0.70

    /** The channel name a whole-screen crop reports. */
    const val WHOLE_SCREEN_CHANNEL = "UI_AUTOMATION"

    /** Surface readbacks: the SDK renderer's own output, with no app chrome and no view overlay. */
    const val PIXEL_COPY_CHANNEL = "PIXEL_COPY_SURFACE"
    const val TEXTURE_VIEW_CHANNEL = "TEXTURE_VIEW"

    /** A sample whose capture produced nothing. Never silently dropped; counted and asserted. */
    const val UNJUDGED_CHANNEL = "none"

    val SURFACE_CHANNELS = setOf(PIXEL_COPY_CHANNEL, TEXTURE_VIEW_CHANNEL)

    /** The exact colour `GoogleFogSafetyOverlay` paints. */
    const val COVER_RED = 0x3C
    const val COVER_GREEN = 0x3D
    const val COVER_BLUE = 0x3A

    /** Classification tolerance for a scaled/compressed readback. */
    const val COVER_TOLERANCE = 6

    /**
     * Proof tolerance, matching `GoogleProductionLauncherMapHostTest.assertFogCoverPixels`'s window
     * exactly. That helper is private to its own class, so the window is reproduced rather than
     * called; the numbers are the same +-2 per channel plus a hard `alpha == 255`.
     */
    const val COVER_PROOF_TOLERANCE = 2

    data class Tally(
        val analyzedPx: Int,
        val excludedPx: Int,
        val exposedPx: Int,
        val largestClusterPx: Int,
    ) {
        val exposedPct: Double
            get() = if (analyzedPx <= 0) 0.0 else exposedPx * 100.0 / analyzedPx

        val largestClusterPct: Double
            get() = if (analyzedPx <= 0) 0.0 else largestClusterPx * 100.0 / analyzedPx

        val excludedPct: Double
            get() = excludedPx * 100.0 / (analyzedPx + excludedPx).coerceAtLeast(1)

        fun describe(): String =
            "analyzed=$analyzedPx excluded=$excludedPx exposed=$exposedPx " +
                "cluster=$largestClusterPx exposedPct=${"%.3f".format(exposedPct)} " +
                "clusterPct=${"%.3f".format(largestClusterPct)}"
    }

    /** True for the opaque safety cover, which hides ground exactly as fog does. */
    fun isSafetyCover(pixel: Int): Boolean =
        abs(Color.red(pixel) - COVER_RED) <= COVER_TOLERANCE &&
            abs(Color.green(pixel) - COVER_GREEN) <= COVER_TOLERANCE &&
            abs(Color.blue(pixel) - COVER_BLUE) <= COVER_TOLERANCE

    /** The strict proof window: opaque, and within +-2 of the cover colour on every channel. */
    fun isExactSafetyCover(pixel: Int): Boolean =
        Color.alpha(pixel) == 255 &&
            abs(Color.red(pixel) - COVER_RED) <= COVER_PROOF_TOLERANCE &&
            abs(Color.green(pixel) - COVER_GREEN) <= COVER_PROOF_TOLERANCE &&
            abs(Color.blue(pixel) - COVER_BLUE) <= COVER_PROOF_TOLERANCE

    fun regionFor(bitmap: Bitmap, channel: String): Rect {
        val inset = Rect(
            EDGE_INSET_PX,
            EDGE_INSET_PX,
            bitmap.width - EDGE_INSET_PX,
            bitmap.height - EDGE_INSET_PX,
        )
        if (channel != WHOLE_SCREEN_CHANNEL) return inset
        return Rect(
            inset.left,
            (bitmap.height * BAND_TOP_FRACTION).toInt(),
            inset.right,
            (bitmap.height * BAND_BOTTOM_FRACTION).toInt(),
        )
    }

    /**
     * Counts pixels that are neither fog-family nor the opaque cover, and the largest 8-connected
     * run of them, on the strided grid. The cluster rule is
     * [FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX] so that a leak means the same thing here as
     * it does in SP5's video analyzer.
     */
    fun tally(bitmap: Bitmap, region: Rect, exclusions: List<Rect>): Tally {
        val left = region.left.coerceAtLeast(0)
        val top = region.top.coerceAtLeast(0)
        val right = region.right.coerceAtMost(bitmap.width)
        val bottom = region.bottom.coerceAtMost(bitmap.height)
        val width = right - left
        if (width < SAMPLE_STRIDE_PX || bottom - top < SAMPLE_STRIDE_PX) {
            return Tally(0, 0, 0, 0)
        }
        val columns = (width + SAMPLE_STRIDE_PX - 1) / SAMPLE_STRIDE_PX
        val rows = (bottom - top + SAMPLE_STRIDE_PX - 1) / SAMPLE_STRIDE_PX
        val exposedGrid = BooleanArray(columns * rows)
        val row = IntArray(width)
        var analyzed = 0
        var excluded = 0
        var exposed = 0
        var y = top
        var gridY = 0
        while (y < bottom && gridY < rows) {
            bitmap.getPixels(row, 0, width, left, y, width, 1)
            var x = 0
            var gridX = 0
            while (x < width && gridX < columns) {
                val screenX = left + x
                if (exclusions.none { rect -> rect.contains(screenX, y) }) {
                    analyzed += 1
                    val pixel = row[x]
                    if (!SpikeCaptureSupport.isFogFamily(pixel) && !isSafetyCover(pixel)) {
                        exposed += 1
                        exposedGrid[gridY * columns + gridX] = true
                    }
                } else {
                    excluded += 1
                }
                x += SAMPLE_STRIDE_PX
                gridX += 1
            }
            y += SAMPLE_STRIDE_PX
            gridY += 1
        }
        val scale = SAMPLE_STRIDE_PX * SAMPLE_STRIDE_PX
        return Tally(
            analyzedPx = analyzed * scale,
            excludedPx = excluded * scale,
            exposedPx = exposed * scale,
            largestClusterPx = largestCluster(exposedGrid, columns, rows) * scale,
        )
    }

    private fun largestCluster(grid: BooleanArray, columns: Int, rows: Int): Int {
        val visited = BooleanArray(grid.size)
        var largest = 0
        val stack = ArrayDeque<Int>()
        for (start in grid.indices) {
            if (!grid[start] || visited[start]) continue
            var size = 0
            stack.addLast(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                size += 1
                val cellX = cell % columns
                val cellY = cell / columns
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cellX + dx
                        val ny = cellY + dy
                        if (nx !in 0 until columns || ny !in 0 until rows) continue
                        val neighbour = ny * columns + nx
                        if (grid[neighbour] && !visited[neighbour]) {
                            visited[neighbour] = true
                            stack.addLast(neighbour)
                        }
                    }
                }
            }
            largest = maxOf(largest, size)
        }
        return largest
    }
}

/** One sampled frame: what the screen held, and whether the cover was up while it was taken. */
internal data class ExposureFrame(
    val atMillis: Long,
    /**
     * `map_fog_synchronous_cover_up`: the flag the `ViewOverlay` drawable sets in the same call
     * that raises it. This is the witness for "the user is looking at a blanked map", and it is
     * what every cover clause in this file is judged on.
     */
    val coverUp: Boolean,
    /**
     * `map_fog_cover_up`: the COMPOSITION's view of the same cover, read in the same round trip.
     *
     * `V02-007` section 5b. The product's twenty-second terminal cover deadline is armed on a
     * rising edge of *this* one and cancelled on its falling edge; it does not accumulate. The
     * acceptance run recorded 21,565 ms on [coverUp] with no terminal failure and no published
     * interval, and three mechanisms could produce that - a dip shorter than the sampling period
     * re-arming a fresh window, no published edge at all, or the binding's epoch gate suppressing
     * both the publication and the decision. They are distinguishable only by watching the armed
     * witness beside the visible one, which is what this field exists for. It is measured and
     * reported; nothing is asserted on it yet, and no existing clause was weakened to add it.
     */
    val composeCoverUp: Boolean,
    /**
     * `map_fog_last_cover_interval_ms` as of this frame.
     *
     * The binding writes it only when the coordinator's cover falls, so a change inside the window
     * dates that fall to a frame. A window in which it never changes says the binding never saw
     * one, however the screen behaved.
     */
    val publishedIntervalMillis: Long?,
    /**
     * `map_disposed_at` / `map_entry_destroyed_at`, both written outside the composition's own side
     * effect. A cover that goes down BECAUSE the surface was torn down is not a cover the product
     * lowered; see [PublishedTags].
     */
    val disposedAtMillis: Long?,
    val destroyedAtMillis: Long?,
    val channel: String,
    val tally: GestureExposurePixels.Tally,
) {
    val judged: Boolean get() = tally.analyzedPx > 0

    fun describe(): String =
        "[at=$atMillis cover=$coverUp compose=$composeCoverUp " +
            "interval=$publishedIntervalMillis channel=$channel ${tally.describe()}]"
}

/**
 * Real screen pixels taken while the tag said the cover was up.
 *
 * [mismatched] is the whole point: the harness that shipped before this one computed the same
 * number and threw it away, asserting only that a proof object existed. The repository has already
 * shipped a cover that painted the wrong colour once (`GoogleFogSafetyOverlay`'s `FogCoverDrawable`
 * KDoc records it), and only a per-channel comparison catches that.
 */
internal data class CoverPixelProof(
    val samples: Int,
    val mismatched: Int,
    val detail: String,
) {
    fun describe(): String = "samples=$samples mismatched=$mismatched $detail"
}

/**
 * One reusable readback of the SDK's own render surface.
 *
 * The surface is resolved once and ONE bitmap is allocated for the whole trial. The harness this
 * replaces allocated a fresh full-screen bitmap on every iteration of an unbounded loop, which
 * perturbed the very gesture it was measuring; here the only per-sample cost is the copy itself
 * plus [SAMPLE_INTERVAL_MILLIS] of deliberate idle.
 */
internal class GestureSurfaceCapturer(private val mapView: MapView) {
    private val captureThread =
        HandlerThread("trailveil-gesture-capture").also(HandlerThread::start)
    private val handler = Handler(captureThread.looper)
    private var target: View? = null
    private var buffer: Bitmap? = null
    private var channel: String = GestureExposurePixels.UNJUDGED_CHANNEL

    /** Resolves the render surface, allocates the single reused readback, names the channel. */
    fun open(): String {
        val holder = AtomicReference<View?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            holder.set(findRenderSurface(mapView))
        }
        val view = holder.get()
        target = view
        val width = view?.width ?: 0
        val height = view?.height ?: 0
        channel = when {
            width <= 0 || height <= 0 -> GestureExposurePixels.UNJUDGED_CHANNEL
            view is SurfaceView -> GestureExposurePixels.PIXEL_COPY_CHANNEL
            view is TextureView -> GestureExposurePixels.TEXTURE_VIEW_CHANNEL
            else -> GestureExposurePixels.UNJUDGED_CHANNEL
        }
        if (channel != GestureExposurePixels.UNJUDGED_CHANNEL) {
            buffer = createBitmap(width, height)
        }
        return channel
    }

    fun channel(): String = channel

    fun describe(): String =
        "channel=$channel surface=${target?.javaClass?.simpleName ?: "none"} " +
            "size=${buffer?.width ?: 0}x${buffer?.height ?: 0}"

    /**
     * One frame into the reused buffer. Returns the bitmap on success - the SAME instance every
     * time, valid only until the next call - or null when the channel produced nothing usable.
     */
    fun capture(): Bitmap? {
        val bitmap = buffer ?: return null
        val view = target ?: return null
        val copied = when (view) {
            is SurfaceView -> copyFromSurfaceView(view, bitmap)
            is TextureView -> copyFromTextureView(view, bitmap)
            else -> false
        }
        if (!copied || isDegenerate(bitmap)) return null
        return bitmap
    }

    fun close() {
        buffer?.recycle()
        buffer = null
        target = null
        captureThread.quitSafely()
    }

    private fun copyFromSurfaceView(view: SurfaceView, bitmap: Bitmap): Boolean = try {
        val latch = CountDownLatch(1)
        val status = AtomicReference(PixelCopy.ERROR_UNKNOWN)
        PixelCopy.request(
            view,
            bitmap,
            { result ->
                status.set(result)
                latch.countDown()
            },
            handler,
        )
        latch.await(PIXEL_COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
            status.get() == PixelCopy.SUCCESS
    } catch (_: Exception) {
        false
    }

    /**
     * A real copy, or `false` - never "the argument came back, so it must have worked".
     *
     * `TextureView.getBitmap(Bitmap)` returns the bitmap it was HANDED, unconditionally; when the
     * surface is unavailable it simply skips the copy and hands it straight back. The old check was
     * `!= null`, which is therefore always true. That matters because this class reuses one bitmap
     * for every capture, so a torn-down surface replayed the last good frame forever and the audit
     * went on "measuring" a map that no longer existed - which is exactly what happened after the
     * terminal failure in `V02-007` section 5b. It never produced a false PASS, because a replayed
     * frame is a covered frame and the case fails on duration anyway, but a hole in the oracle is a
     * hole. Gated on availability, and on the SDK handing back the buffer that was passed in.
     */
    private fun copyFromTextureView(view: TextureView, bitmap: Bitmap): Boolean = try {
        view.isAvailable && view.getBitmap(bitmap) === bitmap && view.isAttachedToWindow
    } catch (_: Exception) {
        false
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
                if (pixel == 0 || pixel == Color.BLACK) blackOrTransparent += 1
                x += bitmap.width / 4
            }
            y += bitmap.height / 4
        }
        return sampled > 0 && blackOrTransparent == sampled
    }

    private fun findRenderSurface(view: View): View? {
        if (view is SurfaceView || view is TextureView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findRenderSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val PIXEL_COPY_TIMEOUT_SECONDS = 5L
    }
}

/**
 * Samples the render surface on its own thread, so the driving thread can hold a gesture without
 * pausing between MotionEvents.
 *
 * `map_fog_synchronous_cover_up` is read on the main thread immediately before and immediately
 * after each capture, and a frame counts as covered if the cover was up at either edge: the
 * conservative direction, which never lets a frame taken across a cover transition be judged
 * against a claim it cannot satisfy. Tag reads go through `runOnMainSync` because the Compose
 * `SideEffect` in `GoogleHostedMapSurface` writes eight keyed tags on that thread while this loop
 * runs, and `View`'s tag store is not safe to read concurrently with those writes.
 */
internal class GestureExposureSampler(
    private val mapView: MapView,
    private val capturer: GestureSurfaceCapturer,
    private val exclusions: List<Rect>,
) {
    private val frames = Collections.synchronizedList(ArrayList<ExposureFrame>())
    private val running = AtomicBoolean(false)
    private val coverProof = AtomicReference<CoverPixelProof?>(null)
    private val failure = AtomicReference<Throwable?>(null)
    private var proofAttempts = 0
    private var proofsTaken = 0
    private var worker: Thread? = null

    fun start() {
        running.set(true)
        worker = Thread {
            try {
                while (running.get()) {
                    sampleOnce()
                    SystemClock.sleep(SAMPLE_INTERVAL_MILLIS)
                }
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }.apply {
            // A sampler that dies must be reported by its own harness assertion, not by the
            // process-wide default handler, which would kill the instrumentation run instead.
            setUncaughtExceptionHandler { _, thrown -> failure.set(thrown) }
            start()
        }
    }

    /** Stops sampling and returns every frame taken, oldest first. */
    fun stop(): List<ExposureFrame> {
        running.set(false)
        worker?.join(SAMPLER_JOIN_TIMEOUT_MILLIS)
        worker = null
        return frames.toList()
    }

    /** Non-null once a covered frame was corroborated in real screen pixels. */
    fun coverPixelProof(): CoverPixelProof? = coverProof.get()

    /** Non-null when the sampling thread died; the harness asserts this is null. */
    fun failure(): Throwable? = failure.get()

    fun proofAttempts(): Int = proofAttempts

    private fun sampleOnce() {
        val before = readPublishedTags()
        val capture = capturer.capture()
        val after = readPublishedTags()
        // Straddling OR on BOTH cover witnesses, not on one of them.
        //
        // An earlier version read the synchronous flag either side of the capture and the
        // composition tag only after it, then claimed in this comment that all three came from one
        // round trip. Neither half was true: it was three round trips, and the two witnesses were
        // sampled by different rules, so part of any disagreement between them was the sampler's.
        // Now every tag is read in one `runOnMainSync`, twice, and both covers use the same rule.
        val coverUp = before.coverUp || after.coverUp
        val composeCoverUp = before.composeCoverUp || after.composeCoverUp
        val published = after
        if (capture == null) {
            frames += ExposureFrame(
                atMillis = SystemClock.elapsedRealtime(),
                coverUp = coverUp,
                composeCoverUp = composeCoverUp,
                publishedIntervalMillis = published.intervalMillis,
                disposedAtMillis = published.disposedAt,
                destroyedAtMillis = published.destroyedAt,
                channel = GestureExposurePixels.UNJUDGED_CHANNEL,
                tally = GestureExposurePixels.Tally(0, 0, 0, 0),
            )
            return
        }
        val channel = capturer.channel()
        val scale = if (mapView.width > 0) capture.width.toDouble() / mapView.width else 1.0
        val scaled = exclusions.map { rect ->
            Rect(
                (rect.left * scale).toInt(),
                (rect.top * scale).toInt(),
                (rect.right * scale).toInt(),
                (rect.bottom * scale).toInt(),
            )
        }
        val region = GestureExposurePixels.regionFor(capture, channel)
        frames += ExposureFrame(
            atMillis = SystemClock.elapsedRealtime(),
            coverUp = coverUp,
            composeCoverUp = composeCoverUp,
            publishedIntervalMillis = published.intervalMillis,
            disposedAtMillis = published.disposedAt,
            destroyedAtMillis = published.destroyedAt,
            channel = channel,
            tally = GestureExposurePixels.tally(capture, region, scaled),
        )
        if (coverUp && proofsTaken < COVER_PROOFS_WANTED && proofAttempts < COVER_PROOF_ATTEMPTS) {
            proofAttempts += 1
            corroborateCoverInScreenPixels()
        }
    }

    private fun readSynchronousCover(): Boolean {
        val holder = AtomicReference(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            holder.set(mapView.getTag(R.id.map_fog_synchronous_cover_up) == true)
        }
        return holder.get()
    }

    /**
     * Every per-frame tag, in ONE main-thread round trip.
     *
     * The interval is the BINDING's own view: it moves only when `publishCoverInterval` sees the
     * coordinator's cover fall. The disposal stamps are the product's own, written OUTSIDE the
     * composition side effect that `V02-007` section 5b proves can stall - `map_disposed_at` in
     * `onDispose` and `map_entry_destroyed_at` when the MapView is destroyed - so they are the only
     * way this audit can tell "the cover came down" from "the surface was torn down, which lowers
     * the cover on its way out". Reading them costs nothing here and closes a question the section
     * could not otherwise answer.
     */
    private fun readPublishedTags(): PublishedTags {
        val holder = AtomicReference(PublishedTags(false, false, null, null, null))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            holder.set(
                PublishedTags(
                    coverUp = mapView.getTag(R.id.map_fog_synchronous_cover_up) == true,
                    composeCoverUp = mapView.getTag(R.id.map_fog_cover_up) == true,
                    intervalMillis = mapView.getTag(R.id.map_fog_last_cover_interval_ms) as? Long,
                    disposedAt = mapView.getTag(R.id.map_disposed_at) as? Long,
                    destroyedAt = mapView.getTag(R.id.map_entry_destroyed_at) as? Long,
                ),
            )
        }
        return holder.get()
    }

    private data class PublishedTags(
        val coverUp: Boolean,
        val composeCoverUp: Boolean,
        val intervalMillis: Long?,
        val disposedAt: Long?,
        val destroyedAt: Long?,
    )

    /**
     * The tag says the cover is up; this says the screen agrees.
     *
     * The composited screenshot is the ONLY channel that contains the cover at all: the cover is a
     * `MapView.overlay` drawable, and a surface readback is the SDK renderer's output from beneath
     * the view layer. A capture taken across a cover transition is discarded rather than judged,
     * and the WORST reading is retained, so a single lucky sample cannot bury a mismatch.
     */
    private fun corroborateCoverInScreenPixels() {
        val capture = SpikeScenarioSupport.captureScreenTruth(mapView) ?: return
        val bitmap = capture.bitmap
        try {
            if (!readSynchronousCover()) return
            // Sampled across the SAME region the leak oracle judges, not over the middle third of
            // each axis.
            //
            // Nine central points discharged the whole covered half of the contract, and for the
            // four kinds that require the cover to rise there is nothing else: every in-window
            // frame is covered, so the leak rule is skipped by construction and this is the only
            // thing looking at pixels. A cover that drew the right colour in the centre and left a
            // band bare at an edge - which is exactly what a `FogCoverDrawable.setBounds` racing a
            // layout produces - passed all of them. `GestureExposurePixels.regionFor` is the
            // audit's own chrome-free window for this capture channel, so widening to it adds no
            // app furniture and no new justification: it is the region this class has already
            // argued is map.
            val region = GestureExposurePixels.regionFor(bitmap, capture.method)
            val readings = (0 until COVER_PROOF_GRID_ROWS).flatMap { row ->
                (0 until COVER_PROOF_GRID_COLUMNS).map { column ->
                    val x = region.left +
                        region.width() * (2 * column + 1) / (2 * COVER_PROOF_GRID_COLUMNS)
                    val y = region.top +
                        region.height() * (2 * row + 1) / (2 * COVER_PROOF_GRID_ROWS)
                    x to y
                }
            }.map { (x, y) ->
                val inside = x in 0 until bitmap.width && y in 0 until bitmap.height
                Triple(x to y, inside, if (inside) bitmap[x, y] else 0)
            }
            val mismatched = readings.count { (_, inside, pixel) ->
                !inside || !GestureExposurePixels.isExactSafetyCover(pixel)
            }
            val proof = CoverPixelProof(
                samples = readings.size,
                mismatched = mismatched,
                // Only the mismatches are spelled out. A full grid of matching readings is
                // hundreds of identical hex words and would bury the one that differs.
                detail = "bitmap=${bitmap.width}x${bitmap.height} " +
                    "grid=${COVER_PROOF_GRID_COLUMNS}x${COVER_PROOF_GRID_ROWS} " +
                    "region=${region.width()}x${region.height()} mismatches=[" +
                    readings.filter { (_, inside, pixel) ->
                        !inside || !GestureExposurePixels.isExactSafetyCover(pixel)
                    }.joinToString(" ") { (point, inside, pixel) ->
                        "${point.first},${point.second}=" +
                            if (inside) String.format("%08X", pixel) else "OUT_OF_BITMAP"
                    } +
                    "]",
            )
            proofsTaken += 1
            val incumbent = coverProof.get()
            if (incumbent == null || proof.mismatched > incumbent.mismatched) {
                coverProof.set(proof)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val SAMPLER_JOIN_TIMEOUT_MILLIS = 15_000L

        /**
         * The cover-proof grid, over the judged region rather than over the centre.
         *
         * 240 points over `GestureExposurePixels.regionFor`, which for a composited capture is
         * the chrome-free band: full width, but only 0.34 to 0.70 of the height, because the entry
         * screen's own controls draw ABOVE the cover outside it and would read as a mismatch.
         *
         * So this widened the proof horizontally, from a third of the width to nearly all of it,
         * and left its vertical extent roughly where it already was. A bare band INSIDE that
         * region has to be narrower than one row - about 90 px on a 1080 x 2400 map - to slip
         * between samples, which no cover-bounds defect would leave. A bare band at the very top
         * or bottom of the map is outside the region and is not sampled here at all. That residual
         * is real and is recorded in the task's evidence rather than claimed away: for the kinds
         * that require the cover to rise, the whole-frame witness is the leak rule, and that rule
         * is skipped by construction while every in-window frame is covered.
         */
        const val COVER_PROOF_GRID_COLUMNS = 12
        const val COVER_PROOF_GRID_ROWS = 20

        /**
         * Deliberate idle between captures. A `PixelCopy` of a full-screen surface costs tens of
         * milliseconds; back-to-back copies starve the renderer whose frames are being judged.
         * At this period a 1.5 s gesture window still yields well above
         * [GestureExposureVerdict.MINIMUM_IN_WINDOW_FRAMES] frames.
         */
        const val SAMPLE_INTERVAL_MILLIS = 40L

        /** Screenshots are expensive; two successful corroborations per trial is the budget. */
        const val COVER_PROOFS_WANTED = 2
        const val COVER_PROOF_ATTEMPTS = 6
    }
}

/**
 * What one gesture kind is allowed to be, and what it must have achieved.
 *
 * Every field here is an assertion the trial cannot pass without. The harness this replaces printed
 * `zoomDelta`, `tiltDelta`, `bearingDelta` and the touch-down count into an evidence line and
 * checked none of them, so a gesture the SDK swallowed entirely would have passed on the strength
 * of the post-settle frames alone. The magnitudes are the MapLibre originals'
 * (`MapSurfaceTest.MINIMUM_GESTURE_ZOOM_CHANGE = 1.5`,
 * `MINIMUM_ACCEPTED_SHOVE_TILT_DEGREES = 15.0`, `MINIMUM_ACCEPTED_ROTATE_DEGREES = 20.0`,
 * `MINIMUM_TAP_ZOOM_CHANGE = 0.5`).
 */
internal enum class GestureKind(
    val label: String,
    val minimumZoomOut: Float? = null,
    val minimumZoomIn: Float? = null,
    val minimumTiltDegrees: Float? = null,
    val minimumBearingDegrees: Float? = null,
    /**
     * True where the gesture provably leaves the published surround, so `coverRose` may be
     * required rather than merely bounded when it happens. Every zoom-OUT kind qualifies: the
     * viewport grows past the tile set the installed generation published, which is exactly the
     * condition `FogOverlaySurfaceCoordinator.onCameraMoveFrame` raises the cover on. Zoom-IN and
     * rotation are left unrequired because whether the SDK's new key set is still covered by the
     * published one is a property of the coverage planner, not of the gesture.
     */
    val requiresCoverToRise: Boolean = false,
    /**
     * Extra audited window after the last injected UP. Held gestures end when the fingers lift;
     * a tap-triggered zoom is a gesture whose camera movement is the animation the SDK runs on the
     * gesture's behalf, and those frames are the ones the MapLibre tap cases audit.
     */
    val animationTailMillis: Long = 0L,
    /** Zoom levels of room the start camera must have below it for the requested travel. */
    val startZoomHeadroomBelow: Float = 0f,
    /** Zoom levels of room the start camera must have above it for the requested travel. */
    val startZoomHeadroomAbove: Float = 0f,
) {
    PINCH_ZOOM_OUT(
        label = "heldTwoFingerPinchZoomOut",
        minimumZoomOut = 1.5f,
        requiresCoverToRise = true,
        startZoomHeadroomBelow = 2.5f,
    ),
    QUICK_ZOOM_OUT(
        label = "oneFingerQuickZoomOut",
        minimumZoomOut = 1.5f,
        requiresCoverToRise = true,
        startZoomHeadroomBelow = 2.5f,
    ),
    TWO_FINGER_TAP_ZOOM_OUT(
        label = "twoFingerTapZoomOut",
        minimumZoomOut = 0.5f,
        requiresCoverToRise = true,
        animationTailMillis = 1_500L,
        startZoomHeadroomBelow = 1.5f,
    ),
    DOUBLE_TAP_ZOOM_IN(
        label = "doubleTapZoomIn",
        minimumZoomIn = 0.5f,
        animationTailMillis = 1_500L,
        startZoomHeadroomAbove = 1.5f,
    ),
    TWO_FINGER_ROTATE(
        label = "twoFingerRotate",
        minimumBearingDegrees = 20f,
    ),
    SHOVE_THEN_HELD_PINCH(
        label = "twoFingerShoveThenHeldPinch",
        minimumZoomOut = 1.5f,
        minimumTiltDegrees = 15f,
        requiresCoverToRise = true,
        startZoomHeadroomBelow = 2.5f,
    ),
}

/** A start camera for a trial. Reached exactly or the trial fails; never silently clamped. */
internal data class GestureStartCamera(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val zoom: Float,
    val tilt: Float = 0f,
    val bearing: Float = 0f,
)

/**
 * What one injected gesture actually did, reported by the driver rather than inferred.
 *
 * [downAtMillis] and [upAtMillis] delimit the ACCEPTED stream on the sampler's own clock, so the
 * pixel claim can be evaluated over the gesture instead of over the post-gesture settle.
 * [injectedDownCount] counts every `ACTION_DOWN` the driver injected, including the ones belonging
 * to rejected engagement attempts, because `GestureOwningGoogleMapView.dispatchTouchEvent`
 * increments `map_touch_down_count` on `ACTION_DOWN` alone - never on `ACTION_POINTER_DOWN`.
 */
internal data class GestureDrive(
    val note: String,
    val downAtMillis: Long,
    val upAtMillis: Long,
    val injectedDownCount: Int,
    val attempts: Int,
    /**
     * The camera tilt read at the ACCEPTED attempt's first DOWN, for composites that retry.
     *
     * A retried composite does not re-settle the start camera between attempts, so a rejected
     * attempt's tilt is still on the camera when the next one opens. Judging the trial's tilt from
     * the settled start camera therefore lets a residue from an attempt whose gesture was never
     * accepted discharge the row's claim, which is that the tilt of THIS interaction survives the
     * re-grab. Null for kinds that do not tilt, where the settled start camera is the right
     * baseline.
     */
    val tiltAtAcceptedDownDegrees: Float? = null,
)

/** The settled reference at one camera on one channel: the scale a leak is judged against. */
internal data class FloorReading(
    val channel: String,
    val frames: Int,
    val analyzedPx: Int,
    val excludedPct: Double,
    val exposedPct: Double,
    val largestClusterPx: Int,
) {
    val largestClusterPct: Double
        get() = largestClusterPx * 100.0 / analyzedPx.coerceAtLeast(1)

    fun describe(): String =
        "channel=$channel frames=$frames analyzedPx=$analyzedPx " +
            "excludedPct=${"%.2f".format(excludedPct)} " +
            "exposedPct=${"%.3f".format(exposedPct)} clusterPx=$largestClusterPx " +
            "clusterPct=${"%.3f".format(largestClusterPct)}"

    companion object {
        /** The worse of two readings on the same channel, field by field. */
        fun envelope(first: FloorReading, second: FloorReading): FloorReading = FloorReading(
            channel = first.channel,
            frames = first.frames + second.frames,
            analyzedPx = minOf(first.analyzedPx, second.analyzedPx),
            excludedPct = maxOf(first.excludedPct, second.excludedPct),
            exposedPct = maxOf(first.exposedPct, second.exposedPct),
            largestClusterPx = maxOf(first.largestClusterPx, second.largestClusterPx),
        )

        fun of(channel: String, frames: List<ExposureFrame>): FloorReading = FloorReading(
            channel = channel,
            frames = frames.size,
            analyzedPx = frames.minOf { frame -> frame.tally.analyzedPx },
            excludedPct = frames.maxOf { frame -> frame.tally.excludedPct },
            exposedPct = frames.maxOf { frame -> frame.tally.exposedPct },
            largestClusterPx = frames.maxOf { frame -> frame.tally.largestClusterPx },
        )
    }
}

/** The fog-detached arm at one trial's own start camera: the sensitivity proof and the scale. */
internal data class BareReading(
    val name: String,
    val channel: String,
    val appliedZoom: Float,
    val exposedPct: Double,
    val largestClusterPx: Int,
    val analyzedPx: Int,
    /** See [GestureTrialReport.exclusionsGuessed]; the same caveat applies to this reading. */
    val exclusionsGuessed: Boolean,
) {
    val largestClusterPct: Double
        get() = largestClusterPx * 100.0 / analyzedPx.coerceAtLeast(1)

    fun describe(): String =
        "bareChannel=$channel bareZoom=${"%.3f".format(appliedZoom)} " +
            "bareExposedPct=${"%.2f".format(exposedPct)} bareClusterPx=$largestClusterPx " +
            "bareClusterPct=${"%.3f".format(largestClusterPct)} " +
            "bareExclusionsGuessed=$exclusionsGuessed"
}

/** Everything one trial measured, so a failure names the trial rather than a bare pixel count. */
internal data class GestureTrialReport(
    val kind: GestureKind,
    val camera: GestureStartCamera,
    val before: CameraPosition,
    val after: CameraPosition,
    val drive: GestureDrive,
    val startFloors: Map<String, FloorReading>,
    val endFloors: Map<String, FloorReading>,
    val frames: List<ExposureFrame>,
    val coverRose: Boolean,
    val longestCoveredRunMillis: Long,
    /** The same longest run measured on the composition witness the deadline is armed on. */
    val longestComposeCoveredRunMillis: Long,
    /** Rising edges of each witness across the trial. See `coverRises`. */
    val coverRises: Int,
    val composeCoverRises: Int,
    /** False where sampling stopped with the cover still up; see [longestCoveredRunMillis]. */
    val coverSettled: Boolean,
    /** Composition epoch and binding identity across the trial. See `effectEpoch`. */
    val epochBefore: String?,
    val epochAfter: String?,
    val bindingBefore: String?,
    val bindingAfter: String?,
    /** Where each witness was up within the sampled window. See `witnessShape`. */
    val coverShape: String,
    val composeCoverShape: String,
    /** When the binding's published interval moved inside the window, if it moved. */
    val intervalShape: String,
    /** The worst inter-frame gap, and whether the surface stamped itself torn down. */
    val maxFrameGapMillis: Long,
    val maxFrameGapStartMillis: Long,
    val teardownShape: String,
    /**
     * Whether a hosted MapView is still in the window when the trial ends.
     *
     * The decisive witness for "did the surface go terminal", and the one this audit lacked. A
     * terminal failure makes the host swap `GoogleHostedMapSurface` for the provider-unavailable
     * surface, which builds no MapView, so the tree stops carrying one. It does not travel through a
     * composition side effect and it cannot be faked by a stale tag. `false` here beside a
     * `disposedSeenAtMs` says the cover came down because the surface was torn down - which is the
     * product failing closed, not a deadline being missed.
     */
    val mapViewPresentAfter: Boolean,
    /** The binding's own account of why the cover was up when the trial ended. */
    val phaseAfter: String?,
    /** Host ON_STOP / ON_START seen while this trial ran. See the harness's counters. */
    val hostStopsDuringTrial: Int,
    val hostStartsDuringTrial: Int,
    /** Which lifecycle those two were counted on - `NavBackStackEntry`, or the activity. */
    val hostLifecycle: String,
    val coverIntervalBeforeMillis: Long?,
    val coverIntervalAfterMillis: Long?,
    val coverPixelProof: CoverPixelProof?,
    val coverProofAttempts: Int,
    val generationBefore: Long?,
    val generationAfter: Long?,
    val touchDownsBefore: Int,
    val touchDownsAfter: Int,
    /**
     * Whether the SDK decorations this trial excluded were LOCATED or GUESSED.
     *
     * `SpikeCaptureSupport.liveExclusionRects` reports it and every caller here used to drop it on
     * the floor with `.first`, which is how a run that could not find the watermark or the compass
     * produced a verdict indistinguishable from one that measured them. It matters in both
     * directions: a guessed rect can sit where no decoration is, hiding real exposed basemap under
     * an exclusion, or miss the decoration entirely and let it count as a leak. Reported rather
     * than asserted on, because the guess is an environment fact - the SDK's tag is not this
     * repository's to guarantee - and failing a fog audit for it would be blaming the fog for the
     * locator.
     */
    val exclusionsGuessed: Boolean,
    val samplerFailure: Throwable?,
) {
    val motionEndMillis: Long get() = drive.upAtMillis + kind.animationTailMillis

    val inWindowFrames: List<ExposureFrame>
        get() = frames.filter { frame ->
            frame.atMillis >= drive.downAtMillis && frame.atMillis <= motionEndMillis
        }

    val afterWindowFrames: List<ExposureFrame>
        get() = frames.filter { frame -> frame.atMillis > motionEndMillis }

    val zoomDelta: Float get() = after.zoom - before.zoom

    /**
     * Measured from the accepted attempt's own DOWN when the drive recorded one, so a rejected
     * earlier attempt's tilt residue cannot discharge this trial's claim.
     */
    val tiltDelta: Float
        get() = after.tilt - (drive.tiltAtAcceptedDownDegrees ?: before.tilt)

    /** Signed shortest turn, so 350 -> 10 degrees reads as +20 rather than -340. */
    val bearingDelta: Float
        get() = ((after.bearing - before.bearing) % 360f + 540f) % 360f - 180f

    val touchDownGrowth: Int get() = touchDownsAfter - touchDownsBefore

    /** Per-frame reference: the start floor inside the gesture, the envelope of both after it. */
    fun floorFor(frame: ExposureFrame): FloorReading? {
        val start = startFloors[frame.channel]
        if (frame.atMillis <= motionEndMillis) return start
        val end = endFloors[frame.channel]
        return when {
            start == null -> end
            end == null -> start
            else -> FloorReading.envelope(start, end)
        }
    }

    fun describe(): String {
        val inWindow = inWindowFrames
        return "kind=${kind.label} camera=${camera.name} ${drive.note} " +
            "attempts=${drive.attempts} windowMs=${motionEndMillis - drive.downAtMillis} " +
            "frames=${frames.size} inWindow=${inWindow.size} " +
            "inWindowUnjudged=" +
            "${inWindow.count { it.channel == GestureExposurePixels.UNJUDGED_CHANNEL }} " +
            "inWindowCovered=${inWindow.count { it.coverUp }} " +
            "channels=${frames.map { it.channel }.distinct().sorted().joinToString("|")} " +
            "startFloor=[${startFloors.values.joinToString(";") { it.describe() }}] " +
            "endFloor=[${endFloors.values.joinToString(";") { it.describe() }}] " +
            "worstInWindowExposedPct=" +
            "${"%.3f".format(inWindow.filter { !it.coverUp }
                .maxOfOrNull { it.tally.exposedPct } ?: 0.0)} " +
            "worstInWindowClusterPx=" +
            "${inWindow.filter { !it.coverUp }.maxOfOrNull { it.tally.largestClusterPx } ?: 0} " +
            "coverRose=$coverRose longestCoveredRunMs=$longestCoveredRunMillis " +
            "longestComposeCoveredRunMs=$longestComposeCoveredRunMillis " +
            "coverRises=$coverRises composeCoverRises=$composeCoverRises " +
            "coverSettled=$coverSettled epoch=$epochBefore->$epochAfter " +
            "binding=$bindingBefore->$bindingAfter phaseAfter=[$phaseAfter] " +
            "coverShape=[$coverShape] composeCoverShape=[$composeCoverShape] " +
            "intervalShape=[$intervalShape] maxFrameGapMs=$maxFrameGapMillis " +
            "maxFrameGapAtMs=$maxFrameGapStartMillis teardown=[$teardownShape] " +
            "mapViewPresentAfter=$mapViewPresentAfter " +
            "hostStops=$hostStopsDuringTrial hostStarts=$hostStartsDuringTrial " +
            "hostLifecycle=$hostLifecycle " +
            "coverIntervalMs=$coverIntervalBeforeMillis->$coverIntervalAfterMillis " +
            "coverProof=${coverPixelProof?.describe() ?: "none"} " +
            "coverProofAttempts=$coverProofAttempts " +
            "startZoom=${"%.3f".format(before.zoom)} " +
            "zoomDelta=${"%.3f".format(zoomDelta)} tiltDelta=${"%.2f".format(tiltDelta)} " +
            "bearingDelta=${"%.2f".format(bearingDelta)} " +
            "generation=$generationBefore->$generationAfter " +
            "touchDowns=$touchDownsBefore->$touchDownsAfter " +
            "injectedDowns=${drive.injectedDownCount} " +
            "exclusionsGuessed=$exclusionsGuessed " +
            "samplerFailure=${samplerFailure?.javaClass?.simpleName ?: "none"}"
    }
}

/**
 * Drives the real production launcher for one gesture trial: reach the start camera with proven
 * canonical fog, measure the settled noise floor, sample the surface through the gesture and its
 * settle, measure the settled floor again at the camera the gesture reached, and hand back
 * everything measured.
 */
internal class GestureExposureHarness private constructor(
    val mapView: MapView,
    val map: GoogleMap,
    private val capturer: GestureSurfaceCapturer,
    /**
     * ON_STOP / ON_START seen on the host since [attach], counted live.
     *
     * `V02-007` section 5b. The binding's twenty-second cover deadline is cancelled by
     * `onHostStopped` and re-armed as a *fresh* window by `onHostStarted`, so a stop/start cycle
     * mid-cover would restart the window.
     *
     * Counted on the lifecycle the BINDING arms from, which is not always the activity's.
     *
     * `GoogleHostedMapSurface` arms from `LocalLifecycleOwner`, and inside the navigation host that
     * is the back-stack entry, not the activity. A back-stack entry can leave STARTED without the
     * activity doing so, so a counter on the activity would report `hostStops == 0` for a stop the
     * binding did see - which is exactly the wrong direction for a witness whose job is to rule a
     * re-arm out. `AndroidView` publishes the composition's owner onto the view tree it creates, so
     * asking the MapView for its view-tree lifecycle owner gets the same object the binding holds.
     * Which one was found is reported as `hostLifecycle` rather than assumed; the activity is the
     * fallback, and it is named as a fallback when it is used.
     */
    private val hostStops: AtomicInteger,
    private val hostStarts: AtomicInteger,
    /** Which lifecycle the two counters above are actually watching. */
    private val hostLifecycleOwner: String,
    /** Held so a trial can ask whether the surface under audit is still in the tree at all. */
    private val decorView: View,
) {
    fun cameraPosition(): CameraPosition = onMain { map.cameraPosition }

    fun minimumZoom(): Float = onMain { map.minZoomLevel }

    fun maximumZoom(): Float = onMain { map.maxZoomLevel }

    /** True when the visible region really straddles the antimeridian at this camera. */
    fun visibleRegionCrossesTheSeam(): Boolean = onMain {
        val bounds = map.projection.visibleRegion.latLngBounds
        bounds.northeast.longitude < bounds.southwest.longitude
    }

    fun generation(): Long? = onMain {
        (mapView.getTag(R.id.map_fog_canonical_generation) as? String)?.toLongOrNull()
    }

    fun coverUp(): Boolean? = onMain { mapView.getTag(R.id.map_fog_cover_up) as? Boolean }

    fun flightActive(): Boolean = onMain { mapView.getTag(R.id.map_camera_flight_active) == true }

    fun basemapLoadState(): String? = onMain {
        mapView.getTag(R.id.map_basemap_load_state) as? String
    }

    fun publishedCoverIntervalMillis(): Long? = onMain {
        mapView.getTag(R.id.map_fog_last_cover_interval_ms) as? Long
    }

    /**
     * The composition effect epoch and the binding instance behind the fog surface.
     *
     * `V02-007` section 5b. `map_fog_last_cover_interval_ms` is published by the binding on the
     * cover's falling edge, and every binding starts with its own `lastPublishedCoverUp = false`.
     * A binding replaced mid-trial therefore sees a falling edge it never saw rise, publishes
     * nothing, and leaves the previous binding's interval standing - which is exactly the pair the
     * acceptance run recorded: a cover the screen watched rise and fall, beside an interval that
     * did not move. Read before and after each trial so that reading is checkable rather than
     * inferred.
     */
    fun effectEpoch(): String? = onMain {
        mapView.getTag(R.id.map_fog_effect_epoch)?.toString()
    }

    fun bindingInstance(): String? = onMain {
        mapView.getTag(R.id.map_fog_binding_instance)?.toString()
    }

    /** `pending=..,reason=..,terminal=..,retry=..` - why the cover is up, and whether it gave up. */
    fun fogPhase(): String? = onMain { mapView.getTag(R.id.map_fog_phase)?.toString() }

    fun touchDownCount(): Int = onMain {
        (mapView.getTag(R.id.map_touch_down_count) as? Int) ?: 0
    }

    fun viewSize(): Pair<Int, Int> = onMain { mapView.width to mapView.height }

    fun viewOrigin(): IntArray = onMain {
        val location = IntArray(2)
        mapView.getLocationOnScreen(location)
        location
    }

    fun diagnostics(): String = onMain {
        "binding=${mapView.getTag(R.id.map_fog_binding_state)} " +
            "phase=${mapView.getTag(R.id.map_fog_phase)} " +
            "gates=${mapView.getTag(R.id.map_fog_binding_gates)} " +
            "lastFogFailure=${mapView.getTag(R.id.map_fog_last_failure)} " +
            "basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
            "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
            "cover=${mapView.getTag(R.id.map_fog_cover_up)} " +
            "syncCover=${mapView.getTag(R.id.map_fog_synchronous_cover_up)} " +
            "flight=${mapView.getTag(R.id.map_camera_flight_active)} " +
            "touchDowns=${(mapView.getTag(R.id.map_touch_down_count) as? Int) ?: 0} " +
            "capture=${capturer.describe()} " +
            "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown}"
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> onMain(block: () -> T): T {
        val holder = AtomicReference<Any?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.set(block()) }
        return holder.get() as T
    }

    /**
     * Reaches the start camera and proves the fog there.
     *
     * Three things this does that the harness it replaces did not.
     *
     * 1. **The clamp may not eat the requested camera.** The SDK's minimum zoom is
     *    viewport-dependent (`V02-004-google-fog.md` records that Google clamps a requested zoom to
     *    it), so a requested camera is checked against the live range BEFORE the move and the
     *    APPLIED camera is checked against the request after it. A trial that silently audits a
     *    clamped camera is the `GoogleSettledFogCoverageSweepTest` defect, not a measurement.
     * 2. **The readiness gate cannot be satisfied by the previous trial's retained state.**
     *    `generation() != null && coverUp() == false` is true on its first poll from whatever the
     *    last trial left behind. The generation published before the move is therefore snapshotted
     *    and the gate waits for a DIFFERENT one - a viewport move dirties the coordinator and
     *    `beginRebuild` mints a new id, which is the same property
     *    `GoogleProductionLauncherMapHostTest` asserts after its fling.
     * 3. **Settled means settled.** Camera, cover, generation and flight flag must all hold for
     *    [CAMERA_STABLE_POLLS] consecutive polls, so a cover about to rise is never mistaken for a
     *    settled scene.
     */
    fun settleAtStartCamera(kind: GestureKind, start: GestureStartCamera): CameraPosition {
        val minimum = minimumZoom()
        val maximum = maximumZoom()
        assertTrue(
            "${kind.label}/${start.name}: the SDK's live zoom range [$minimum, $maximum] leaves " +
                "only ${start.zoom - minimum} levels below the requested start zoom " +
                "${start.zoom}, " +
                "under the ${kind.startZoomHeadroomBelow} this gesture must travel; a clamped " +
                "start camera would audit a different scene from the requested one. " +
                diagnostics(),
            start.zoom - minimum >= kind.startZoomHeadroomBelow,
        )
        assertTrue(
            "${kind.label}/${start.name}: the SDK's live zoom range [$minimum, $maximum] leaves " +
                "only ${maximum - start.zoom} levels above the requested start zoom " +
                "${start.zoom}, " +
                "under the ${kind.startZoomHeadroomAbove} this gesture must travel. " +
                diagnostics(),
            maximum - start.zoom >= kind.startZoomHeadroomAbove,
        )
        val generationBeforeMove = generation()
        val position = CameraPosition.Builder()
            .target(LatLng(start.latitude, start.longitude))
            .zoom(start.zoom)
            .tilt(start.tilt)
            .bearing(start.bearing)
            .build()
        onMain { map.moveCamera(CameraUpdateFactory.newCameraPosition(position)) }
        val settled = awaitStableCamera()
        assertTrue(
            "${kind.label}/${start.name}: the SDK applied zoom ${settled.zoom} for a requested " +
                "${start.zoom}; the requested start camera was never reached, so nothing below " +
                "measures the scene this trial names. " + diagnostics(),
            abs(settled.zoom - start.zoom) <= START_ZOOM_TOLERANCE,
        )
        assertTrue(
            "${kind.label}/${start.name}: canonical fog never installed a generation newer than " +
                "$generationBeforeMove at the start camera, so the readiness gate would have " +
                "been satisfied by the previous trial's retained state. " + diagnostics(),
            awaitUntil(GENERATION_TIMEOUT_MILLIS) { generationIsNew(generationBeforeMove) },
        )
        assertTrue(
            "${kind.label}/${start.name}: the safety cover never came down at the start camera. " +
                diagnostics(),
            awaitUntil(COVER_SETTLE_TIMEOUT_MILLIS) { coverUp() == false },
        )
        assertTrue(
            "${kind.label}/${start.name}: the start scene never held still - camera, cover or " +
                "the programmed-flight flag kept changing across $CAMERA_STABLE_POLLS " +
                "consecutive polls. " + diagnostics(),
            awaitSettledScene(generationBeforeMove),
        )
        return cameraPosition()
    }

    private fun generationIsNew(previous: Long?): Boolean {
        val current = generation() ?: return false
        return previous == null || current != previous
    }

    /**
     * Camera, cover and flight flag all quiet across consecutive polls.
     *
     * The generation conjunct is carried for symmetry with the wait above, not because it adds
     * anything: the edge - a generation newer than the one on screen before the move - has already
     * been proven by then, and re-testing the same predicate against the same snapshot holds by
     * construction. Camera stillness, the cover being down and no flight in progress are what this
     * gate actually decides.
     */
    private fun awaitSettledScene(generationBeforeMove: Long?): Boolean {
        var stable = 0
        var previous: CameraPosition? = null
        return awaitUntil(CAMERA_SETTLE_TIMEOUT_MILLIS) {
            val current = cameraPosition()
            val last = previous
            previous = current
            val settled = last != null && sameCamera(last, current) &&
                coverUp() == false && generationIsNew(generationBeforeMove) && !flightActive()
            stable = if (settled) stable + 1 else 0
            stable >= CAMERA_STABLE_POLLS
        }
    }

    /** Whether the window still holds a hosted MapView. See [GestureTrialReport.mapViewPresentAfter]. */
    private fun hostedMapViewPresent(): Boolean = onMain {
        decorView.findHostedMapView() != null
    }

    /**
     * Live watermark/compass rects, read per trial on the main thread as the locator needs, paired
     * with whether either of them was guessed rather than found. See
     * [GestureTrialReport.exclusionsGuessed] for why the flag travels instead of being discarded.
     */
    fun exclusionRects(): Pair<List<Rect>, Boolean> = onMain {
        SpikeCaptureSupport.liveExclusionRects(mapView)
    }

    /**
     * Runs one trial. [drive] holds the gesture and returns what it injected and when; the sampler
     * runs throughout and continues through the post-gesture settle, so the frames after the cover
     * falls - at the new camera, over never-visited ground - are audited too, against a floor
     * re-measured there rather than against the start camera's.
     */
    fun runTrial(
        kind: GestureKind,
        start: GestureStartCamera,
        drive: (GestureExposureHarness) -> GestureDrive,
    ): GestureTrialReport {
        val before = settleAtStartCamera(kind, start)
        val (exclusions, exclusionsGuessed) = exclusionRects()
        val startFloors = measureFloor("${kind.label}/${start.name} start", exclusions)
        val touchDownsBefore = touchDownCount()
        val generationBefore = generation()
        val coverIntervalBefore = publishedCoverIntervalMillis()
        val epochBefore = effectEpoch()
        val bindingBefore = bindingInstance()
        val hostStopsBefore = hostStops.get()
        val hostStartsBefore = hostStarts.get()
        val sampler = GestureExposureSampler(mapView, capturer, exclusions)
        var driven: GestureDrive? = null
        var frames: List<ExposureFrame> = emptyList()
        var coverSettled = false
        sampler.start()
        try {
            driven = drive(this)
            SystemClock.sleep(POST_GESTURE_SAMPLE_MILLIS)
            // Recorded, not discarded. A false here means sampling stopped with the cover still
            // up, so the longest run below is a LOWER BOUND on a cover that never fell rather than
            // a measurement of one that did - a different claim, and one the report should not be
            // able to make silently.
            coverSettled = awaitUntil(COVER_SETTLE_TIMEOUT_MILLIS) { coverUp() == false }
            SystemClock.sleep(POST_COVER_SAMPLE_MILLIS)
        } finally {
            // Outside the try the sampler would outlive a throwing driver, leaving a readback loop
            // running against this MapView for the rest of the process.
            frames = sampler.stop()
        }
        val gesture = requireNotNull(driven)
        val after = cameraPosition()
        // Before measuring anything at the end camera, ask whether there is still a surface to
        // measure. `V02-007` section 5b: this gesture can drive the surface into terminal failure,
        // and the host then swaps the map for the provider-unavailable surface - so the end floor
        // has nothing to read and the floor helper fails with a capture-channel complaint that
        // names the symptom and hides the cause. This names the cause.
        val surviving = hostedMapViewPresent()
        val teardown = teardownShape(frames)
        assertTrue(
            "${kind.label}/${start.name}: the surface went TERMINAL during this gesture - no " +
                "hosted MapView is left in the window, so the map the user was looking at has " +
                "been replaced by the provider-unavailable surface and will not come back " +
                "without the surface being rebuilt. This is the product failing closed on its " +
                "own twenty-second cover deadline, not a " +
                "harness fault: the fog could not prove a generation for this tilted, zoomed-out " +
                "camera in time. teardown=[$teardown] " +
                "coverShape=[${witnessShape(frames) { frame -> frame.coverUp }}] " +
                "maxFrameGapMs=${maxFrameGapMillis(frames)} " +
                "maxFrameGapAtMs=${maxFrameGapStartMillis(frames)}",
            surviving,
        )
        val endFloors = measureFloor("${kind.label}/${start.name} end", exclusions)
        return GestureTrialReport(
            kind = kind,
            camera = start,
            before = before,
            after = after,
            drive = gesture,
            startFloors = startFloors,
            endFloors = endFloors,
            frames = frames,
            coverRose = frames.any { frame -> frame.coverUp },
            longestCoveredRunMillis = longestCoveredRun(frames),
            longestComposeCoveredRunMillis =
                longestCoveredRun(frames) { frame -> frame.composeCoverUp },
            coverRises = coverRises(frames) { frame -> frame.coverUp },
            composeCoverRises = coverRises(frames) { frame -> frame.composeCoverUp },
            coverSettled = coverSettled,
            epochBefore = epochBefore,
            epochAfter = effectEpoch(),
            bindingBefore = bindingBefore,
            bindingAfter = bindingInstance(),
            hostStopsDuringTrial = hostStops.get() - hostStopsBefore,
            hostStartsDuringTrial = hostStarts.get() - hostStartsBefore,
            hostLifecycle = hostLifecycleOwner,
            coverShape = witnessShape(frames) { frame -> frame.coverUp },
            composeCoverShape = witnessShape(frames) { frame -> frame.composeCoverUp },
            intervalShape = intervalShape(frames),
            maxFrameGapMillis = maxFrameGapMillis(frames),
            maxFrameGapStartMillis = maxFrameGapStartMillis(frames),
            teardownShape = teardownShape(frames),
            mapViewPresentAfter = hostedMapViewPresent(),
            phaseAfter = fogPhase(),
            coverIntervalBeforeMillis = coverIntervalBefore,
            coverIntervalAfterMillis = publishedCoverIntervalMillis(),
            coverPixelProof = sampler.coverPixelProof(),
            coverProofAttempts = sampler.proofAttempts(),
            generationBefore = generationBefore,
            generationAfter = generation(),
            touchDownsBefore = touchDownsBefore,
            touchDownsAfter = touchDownCount(),
            exclusionsGuessed = exclusionsGuessed,
            samplerFailure = sampler.failure(),
        )
    }

    /**
     * The settled baseline at the current camera: what the oracle reads when the fog is
     * unquestionably correct. Keyed by capture channel, because a frame may only ever be judged
     * against a floor measured through the same channel - a composited capture contains app chrome
     * and the view-layer cover, a surface readback contains neither.
     */
    fun measureFloor(what: String, exclusions: List<Rect>): Map<String, FloorReading> {
        val sampler = GestureExposureSampler(mapView, capturer, exclusions)
        sampler.start()
        SystemClock.sleep(FLOOR_SAMPLE_MILLIS)
        val frames = sampler.stop().filter { frame -> frame.judged && !frame.coverUp }
        assertTrue(
            "$what: the sampler thread died while measuring the settled floor: " +
                "${sampler.failure()}. " + diagnostics(),
            sampler.failure() == null,
        )
        val byChannel = frames.groupBy { frame -> frame.channel }
            .mapValues { (channel, channelFrames) -> FloorReading.of(channel, channelFrames) }
        val surface = byChannel.filterKeys { channel ->
            channel in GestureExposurePixels.SURFACE_CHANNELS
        }
        assertTrue(
            "$what: no judged uncovered frame arrived on a surface readback channel " +
                "(saw ${byChannel.keys}); a whole-frame fog audit cannot use the composited " +
                "screenshot channel, because the entry screen's chrome above the map would be " +
                "indistinguishable from a basemap leak. " + diagnostics(),
            surface.isNotEmpty(),
        )
        val worst = requireNotNull(surface.values.maxByOrNull { it.frames })
        assertTrue(
            "$what: only ${worst.frames} settled floor frames on channel ${worst.channel}, " +
                "under the $MINIMUM_FLOOR_FRAMES needed for a reference. " + diagnostics(),
            worst.frames >= MINIMUM_FLOOR_FRAMES,
        )
        assertTrue(
            "$what: ${"%.2f".format(worst.excludedPct)}% of the map was excluded as SDK chrome, " +
                "which is enough to hide a leak. " + diagnostics(),
            worst.excludedPct <= EXCLUDED_PCT_BOUND,
        )
        return surface
    }

    private fun longestCoveredRun(
        frames: List<ExposureFrame>,
        covered: (ExposureFrame) -> Boolean = ExposureFrame::coverUp,
    ): Long {
        var longest = 0L
        var runStart = -1L
        var previousCovered = false
        var previousAt = 0L
        frames.forEach { frame ->
            val isCovered = covered(frame)
            if (isCovered && !previousCovered) runStart = frame.atMillis
            if (!isCovered && previousCovered && runStart >= 0L) {
                longest = maxOf(longest, previousAt - runStart)
            }
            previousCovered = isCovered
            previousAt = frame.atMillis
        }
        if (previousCovered && runStart >= 0L) longest = maxOf(longest, previousAt - runStart)
        return longest
    }

    /**
     * Where a witness was up, in milliseconds from the first sampled frame.
     *
     * `V02-007` section 5b. The longest run alone cannot say WHICH end of the trial a witness was
     * up at, and the reproduction turns on exactly that: the two witnesses reported runs 6,792 ms
     * apart with one rise each, which is either a late rise on one or an early fall on the other,
     * and those are opposite findings. Derived from the frames already captured, so it costs
     * nothing on the device.
     */
    private fun witnessShape(
        frames: List<ExposureFrame>,
        covered: (ExposureFrame) -> Boolean,
    ): String {
        if (frames.isEmpty()) return "none"
        val origin = frames.first().atMillis
        val up = frames.filter(covered)
        if (up.isEmpty()) return "never"
        return "first=${up.first().atMillis - origin} last=${up.last().atMillis - origin} " +
            "atFirstFrame=${covered(frames.first())} atLastFrame=${covered(frames.last())} " +
            "spanMs=${frames.last().atMillis - origin}"
    }

    /**
     * The longest gap between consecutive sampled frames.
     *
     * `V02-007` section 5b turns on whether the main thread was starved. This audit makes several
     * `runOnMainSync` round trips per frame, so a looper that could not run a `postDelayed` callback
     * for two seconds could not have run those either - and the gap would show here. A small maximum
     * gap beside an overdue deadline says the looper was NOT starved and something else is going on.
     * Derived from timestamps already captured.
     */
    private fun maxFrameGapMillis(frames: List<ExposureFrame>): Long {
        var worst = 0L
        frames.zipWithNext { a, b -> worst = maxOf(worst, b.atMillis - a.atMillis) }
        return worst
    }

    /**
     * Where the worst gap sits, in ms from the first frame.
     *
     * Size alone does not say what a gap means. A long gap AFTER the surface was torn down is the
     * teardown, and says nothing about whether a deadline could have run earlier; a long gap
     * spanning the moment the deadline was due is the starved looper the section hypothesised.
     */
    private fun maxFrameGapStartMillis(frames: List<ExposureFrame>): Long {
        if (frames.size < 2) return -1L
        val origin = frames.first().atMillis
        var worst = 0L
        var at = -1L
        frames.zipWithNext { a, b ->
            val gap = b.atMillis - a.atMillis
            if (gap > worst) {
                worst = gap
                at = a.atMillis - origin
            }
        }
        return at
    }

    /** When a torn-down surface first stamped itself, in ms from the first sampled frame. */
    private fun teardownShape(frames: List<ExposureFrame>): String {
        if (frames.isEmpty()) return "none"
        val origin = frames.first().atMillis
        val disposed = frames.firstOrNull { frame -> frame.disposedAtMillis != null }
        val destroyed = frames.firstOrNull { frame -> frame.destroyedAtMillis != null }
        if (disposed == null && destroyed == null) return "intact"
        return "disposedSeenAtMs=${disposed?.let { it.atMillis - origin }} " +
            "destroyedSeenAtMs=${destroyed?.let { it.atMillis - origin }}"
    }

    /** When the binding's published interval first changed, in ms from the first sampled frame. */
    private fun intervalShape(frames: List<ExposureFrame>): String {
        if (frames.isEmpty()) return "none"
        val origin = frames.first().atMillis
        val start = frames.first().publishedIntervalMillis
        val changed = frames.firstOrNull { frame -> frame.publishedIntervalMillis != start }
            ?: return "unchanged=$start"
        return "from=$start to=${changed.publishedIntervalMillis} " +
            "atMs=${changed.atMillis - origin}"
    }

    /**
     * How many times the witness went down-to-up across the trial, AS SAMPLED.
     *
     * The longest run alone cannot separate one long cover from a string of short ones, and that is
     * a distinction `V02-007` section 5b turns on: the deadline is re-armed from scratch on every
     * rising edge, so many rises with a long blank means it was restarted rather than missed.
     *
     * What this CANNOT do is prove there was no dip. Frames land roughly every 120 ms, so a fall and
     * a rise inside one sampling period are invisible to it, and that is precisely the case section
     * 5b's first candidate names. `coverRises == 1` therefore means "no dip longer than a sampling
     * period was observed", never "no dip". Ruling that out needs a counter the product increments
     * itself; the honest reading is written down in the section rather than claimed away here.
     */
    private fun coverRises(
        frames: List<ExposureFrame>,
        covered: (ExposureFrame) -> Boolean,
    ): Int {
        var rises = 0
        var previousCovered = false
        frames.forEach { frame ->
            val isCovered = covered(frame)
            if (isCovered && !previousCovered) rises += 1
            previousCovered = isCovered
        }
        return rises
    }

    /**
     * Camera held still AND no programmed flight in progress, across consecutive polls.
     *
     * [awaitStableCamera] answers only the first half and discards its own timeout, so before the
     * launcher's one-shot flight to the newest accepted point has STARTED the camera is trivially
     * stable and that wait returns on its first four polls - leaving the flight free to land in the
     * middle of an audited gesture, which rewrites the scene the trial names. Reported rather than
     * discarded, so a flight that never quiets fails the case instead of silently being audited.
     */
    fun awaitQuietCamera(timeoutMillis: Long = CAMERA_SETTLE_TIMEOUT_MILLIS): Boolean {
        var previous: CameraPosition? = null
        var stable = 0
        return awaitUntil(timeoutMillis) {
            val current = cameraPosition()
            val last = previous
            previous = current
            stable = if (
                last != null && sameCamera(last, current) && !flightActive()
            ) {
                stable + 1
            } else {
                0
            }
            stable >= CAMERA_STABLE_POLLS
        }
    }

    fun awaitStableCamera(): CameraPosition {
        var previous: CameraPosition? = null
        var stable = 0
        awaitUntil(CAMERA_SETTLE_TIMEOUT_MILLIS) {
            val current = cameraPosition()
            val last = previous
            stable = if (last != null && sameCamera(last, current)) stable + 1 else 0
            previous = current
            stable >= CAMERA_STABLE_POLLS
        }
        return cameraPosition()
    }

    private fun sameCamera(a: CameraPosition, b: CameraPosition): Boolean =
        abs(a.target.latitude - b.target.latitude) <= CAMERA_STABLE_DEGREES &&
            abs(a.target.longitude - b.target.longitude) <= CAMERA_STABLE_DEGREES &&
            abs(a.zoom - b.zoom) <= CAMERA_STABLE_ZOOM &&
            abs(a.tilt - b.tilt) <= CAMERA_STABLE_DEGREES_F &&
            abs(a.bearing - b.bearing) <= CAMERA_STABLE_DEGREES_F

    fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return condition()
    }

    fun close() = capturer.close()

    companion object {
        const val MINIMUM_FLOOR_FRAMES = 3
        const val FLOOR_SAMPLE_MILLIS = 1_600L
        const val POST_GESTURE_SAMPLE_MILLIS = 900L
        const val POST_COVER_SAMPLE_MILLIS = 700L
        const val GENERATION_TIMEOUT_MILLIS = 45_000L
        const val COVER_SETTLE_TIMEOUT_MILLIS = 25_000L
        const val CAMERA_SETTLE_TIMEOUT_MILLIS = 12_000L
        const val CAMERA_STABLE_POLLS = 4
        const val CAMERA_STABLE_DEGREES = 0.000_001
        const val CAMERA_STABLE_DEGREES_F = 0.01f
        const val CAMERA_STABLE_ZOOM = 0.001f

        /** The applied start zoom must be the requested one; a clamp is a failure, not a scene. */
        const val START_ZOOM_TOLERANCE = 0.05f

        /** SP1's bound, reused unchanged. */
        const val EXCLUDED_PCT_BOUND = 5.0

        const val POLL_MILLIS = 50L
        const val MAP_READY_TIMEOUT_SECONDS = 30L
        const val MAP_VIEW_POLL_COUNT = 120
        const val MAP_VIEW_POLL_MILLIS = 250L
        const val ONLINE_STATE = "ONLINE"

        fun attach(scenario: ActivityScenario<MainActivity>): GestureExposureHarness {
            val mapViewRef = AtomicReference<MapView>()
            for (attempt in 0 until MAP_VIEW_POLL_COUNT) {
                scenario.onActivity { activity ->
                    mapViewRef.set(activity.window.decorView.findHostedMapView())
                }
                if (mapViewRef.get() != null) break
                SystemClock.sleep(MAP_VIEW_POLL_MILLIS)
            }
            val mapView = requireNotNull(mapViewRef.get()) {
                "the real MainActivity never attached a Google MapView"
            }
            val ready = CountDownLatch(1)
            val mapRef = AtomicReference<GoogleMap>()
            scenario.onActivity {
                mapView.getMapAsync { map ->
                    mapRef.set(map)
                    ready.countDown()
                }
            }
            assertTrue(
                "the production launcher map never became ready",
                ready.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val stops = AtomicInteger(0)
            val starts = AtomicInteger(0)
            val lifecycleOwnerName = AtomicReference("unknown")
            scenario.onActivity { activity ->
                // The owner the composition gave the map, not the activity - see the harness's
                // KDoc on `hostStops`. `AndroidView` publishes `LocalLifecycleOwner` onto the view
                // tree, so this is the same object `GoogleHostedMapSurface` armed its binding from.
                val composed = mapView.findViewTreeLifecycleOwner()
                val owner = composed ?: activity
                lifecycleOwnerName.set(
                    when {
                        composed == null -> "activity(fallback:noViewTreeOwner)"
                        composed === activity -> "activity"
                        else -> composed.javaClass.simpleName
                    },
                )
                owner.lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> stops.incrementAndGet()
                            Lifecycle.Event.ON_START -> starts.incrementAndGet()
                            else -> Unit
                        }
                    },
                )
            }
            val capturer = GestureSurfaceCapturer(mapView)
            val channel = capturer.open()
            assertTrue(
                "the Maps SDK render surface on the production launcher could not be read back " +
                    "directly (${capturer.describe()}); a whole-frame fog audit cannot use the " +
                    "composited screenshot channel, because the entry screen's chrome above the " +
                    "map would be indistinguishable from a basemap leak",
                channel in GestureExposurePixels.SURFACE_CHANNELS,
            )
            val decor = AtomicReference<View>()
            scenario.onActivity { activity -> decor.set(activity.window.decorView) }
            return GestureExposureHarness(
                mapView = mapView,
                map = requireNotNull(mapRef.get()),
                capturer = capturer,
                hostStops = stops,
                hostStarts = starts,
                hostLifecycleOwner = lifecycleOwnerName.get(),
                decorView = requireNotNull(decor.get()),
            )
        }

        private fun View.findHostedMapView(): MapView? {
            if (this is MapView) return this
            if (this !is ViewGroup) return null
            for (index in 0 until childCount) {
                getChildAt(index).findHostedMapView()?.let { return it }
            }
            return null
        }
    }
}

/**
 * The fog-detached arm: the same `GoogleHostedMapSurface` with `fogRequired` false, replaying the
 * cameras the fogged trials actually settled at.
 *
 * This is SP5's falsify mode made mandatory. `GoogleFogFlingExposureSpikeTest` carries the same
 * control behind an opt-in argument, which the parity inventory's NA argument (condition (b))
 * explicitly flags as not self-enforcing; a gesture class whose oracle was never shown to fire in
 * the same run would prove nothing by staying silent.
 */
internal object GestureExposureBareReference {
    const val MAP_READY_TIMEOUT_SECONDS = 30L
    const val MAP_VIEW_POLL_COUNT = 120
    const val MAP_VIEW_POLL_MILLIS = 250L
    const val BASEMAP_POLL_COUNT = 240
    const val POLL_MILLIS = 250L
    const val SETTLE_MILLIS = 1_200L
    const val SAMPLE_COUNT = 4
    const val SAMPLE_INTERVAL_MILLIS = 200L
    const val ONLINE_STATE = "ONLINE"

    /** Reused from the sweep: the fog-detached host must settle at the audited scene's own zoom. */
    const val CALIBRATION_ZOOM_TOLERANCE = 0.25f

    fun measure(
        label: String,
        cameras: List<Pair<String, CameraPosition>>,
    ): Map<String, BareReading> {
        val readings = mutableMapOf<String, BareReading>()
        GoogleMapSurfaceTestHooks.reset()
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.fogRequired = false
        val mapReady = CountDownLatch(1)
        val mapRef = AtomicReference<GoogleMap>()
        val mapViewRef = AtomicReference<MapView>()
        GoogleMapSurfaceTestHooks.onMapReady.set { readyMap ->
            mapRef.set(readyMap)
            mapReady.countDown()
        }
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { view -> mapViewRef.set(view) }
        try {
            ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { _ ->
                assertTrue(
                    "$label: the fog-detached sensitivity host never produced a Google map",
                    mapReady.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                val map = requireNotNull(mapRef.get())
                val mapView = awaitMapView(mapViewRef)
                assertTrue(
                    "$label: the fog-detached sensitivity host never reported a loaded basemap, " +
                        "so this run never showed the oracle seeing bare basemap at all",
                    awaitBasemap(mapView),
                )
                val capturer = GestureSurfaceCapturer(mapView)
                try {
                    val channel = capturer.open()
                    assertTrue(
                        "$label: the fog-detached host's render surface could not be read back " +
                            "directly (${capturer.describe()}), so the sensitivity arm and the " +
                            "audited frames would not share a capture channel",
                        channel in GestureExposurePixels.SURFACE_CHANNELS,
                    )
                    cameras.forEach { (name, camera) ->
                        readings[name] = readOne(label, name, map, mapView, capturer, camera)
                    }
                } finally {
                    capturer.close()
                }
            }
        } finally {
            GoogleMapSurfaceTestHooks.reset()
        }
        return readings
    }

    private fun readOne(
        label: String,
        name: String,
        map: GoogleMap,
        mapView: MapView,
        capturer: GestureSurfaceCapturer,
        camera: CameraPosition,
    ): BareReading {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val applied = AtomicReference<CameraPosition>()
        instrumentation.runOnMainSync {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
            applied.set(map.cameraPosition)
        }
        SystemClock.sleep(SETTLE_MILLIS)
        val settled = AtomicReference<CameraPosition>()
        instrumentation.runOnMainSync { settled.set(map.cameraPosition) }
        val settledCamera = requireNotNull(settled.get())
        assertTrue(
            "$label/$name: the fog-detached host settled at zoom " +
                "${"%.3f".format(settledCamera.zoom)} where the audited scene was measured at " +
                "${"%.3f".format(camera.zoom)}, so the sensitivity arm did not run at the " +
                "audited camera",
            abs(settledCamera.zoom - camera.zoom) <= CALIBRATION_ZOOM_TOLERANCE,
        )
        val exclusionsHolder = AtomicReference<Pair<List<Rect>, Boolean>>()
        instrumentation.runOnMainSync {
            exclusionsHolder.set(SpikeCaptureSupport.liveExclusionRects(mapView))
        }
        val (exclusions, exclusionsGuessed) = requireNotNull(exclusionsHolder.get())
        var worst: GestureExposurePixels.Tally? = null
        repeat(SAMPLE_COUNT) {
            val bitmap = capturer.capture()
            if (bitmap != null) {
                val scale = if (mapView.width > 0) {
                    bitmap.width.toDouble() / mapView.width
                } else {
                    1.0
                }
                val scaled = exclusions.map { rect ->
                    Rect(
                        (rect.left * scale).toInt(),
                        (rect.top * scale).toInt(),
                        (rect.right * scale).toInt(),
                        (rect.bottom * scale).toInt(),
                    )
                }
                val tally = GestureExposurePixels.tally(
                    bitmap,
                    GestureExposurePixels.regionFor(bitmap, capturer.channel()),
                    scaled,
                )
                val incumbent = worst
                if (incumbent == null || tally.exposedPct < incumbent.exposedPct) worst = tally
            }
            SystemClock.sleep(SAMPLE_INTERVAL_MILLIS)
        }
        // The LEAST exposed bare frame is kept, so the sensitivity claim is made at the weakest
        // reading this run produced rather than at its most flattering one.
        val tally = requireNotNull(worst) {
            "$label/$name: the fog-detached host produced no usable capture"
        }
        return BareReading(
            name = name,
            channel = capturer.channel(),
            appliedZoom = settledCamera.zoom,
            exposedPct = tally.exposedPct,
            largestClusterPx = tally.largestClusterPx,
            analyzedPx = tally.analyzedPx,
            exclusionsGuessed = exclusionsGuessed,
        )
    }

    private fun awaitMapView(holder: AtomicReference<MapView>): MapView {
        repeat(MAP_VIEW_POLL_COUNT) {
            holder.get()?.let { return it }
            SystemClock.sleep(MAP_VIEW_POLL_MILLIS)
        }
        return requireNotNull(holder.get()) {
            "the fog-detached sensitivity host never created a MapView"
        }
    }

    private fun awaitBasemap(mapView: MapView): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(BASEMAP_POLL_COUNT) {
            val state = AtomicReference<Any?>(null)
            instrumentation.runOnMainSync {
                state.set(mapView.getTag(R.id.map_basemap_load_state))
            }
            if (state.get() == ONLINE_STATE) return true
            SystemClock.sleep(POLL_MILLIS)
        }
        return false
    }
}

/**
 * The shared verdict. Reported as one line whether it passes or fails, because "the pixels were
 * fine" is only informative next to how many frames were judged, on which channel, over what
 * window, and how long the map was blanked while they were taken.
 */
internal object GestureExposureVerdict {
    const val EVIDENCE_FILE = "v02-007-gesture-exposure.txt"

    /**
     * Upper bound on how long the opaque cover may hide the map for one gesture. Nothing anywhere
     * in the suite bounds Google blanking above today; `GoogleProductionLauncherMapHostTest` only
     * streams `stage6_fling_cover_interval_ms` and asserts it is positive.
     *
     * Justified from what IS recorded on this emulator image. `V02-004-google-fog.md` ("Scale,
     * refresh and provider-failure evidence") measured the final architecture's pan/zoom fog
     * refresh at p95 2,819 ms / max 3,491 ms over a 10,000-point canonical history, and p95
     * 4,357 ms / max 5,869 ms over a 100,000-point one. The fog binding's own
     * `MAXIMUM_COVER_MILLIS` deadline, which terminates the surface, is 20,000 ms. These trials run
     * over never-visited ground on an install with no canonical history, so their refresh component
     * sits at or below the 10k figure, and the cover additionally spans one snapshot proof.
     * 12,000 ms therefore keeps better than a 2x margin over the worst refresh recorded at any
     * UNTILTED scale while still failing well before the product's own terminal deadline.
     *
     * Corrected by this task's own measurement, because the derivation above was overstated for
     * the pose the composite row actually reaches: a programmatic move to a tilted, zoomed-out
     * camera - no gesture, no screen readback - publishes 6,419 ms on this emulator against
     * 1,989 ms untilted, so the real margin there is 1.87x. The number stands, because the
     * composite measures 14.1 s alone and 21.4 s in a suite against either figure. Tighten it once
     * real per-gesture intervals are recorded at each pose; every trial streams its own.
     */
    const val COVER_INTERVAL_BOUND_MILLIS = 12_000L

    /**
     * How much of what the SAME oracle reads at the SAME camera with the fog detached a settled
     * fogged frame is allowed to read as non-fog. Reused unchanged from
     * `GoogleSettledFogCoverageSweepTest.AREA_HALFWAY_FRACTION`: a frame more than half as exposed
     * as its own bare reference is closer to bare than to fogged however it clusters.
     *
     * This replaces an absolute settled-floor percentage, which is not an available oracle on this
     * surface: basemap labels composite ABOVE the fog `TileOverlay` and the repository's own
     * recorded figure is `noiseFloorPctMax=12.247` (`V02-005-spikes.md`, SP5). Any absolute bound
     * near the MapLibre `MAXIMUM_SETTLED_REVEALED_FRACTION` of 0.1% would fail every label-bearing
     * camera on a working product, and a bound above 12% would accept a badly fogged one. The
     * measured bare arm is the only honest scale.
     */
    const val SETTLED_FLOOR_BARE_FRACTION = 0.5

    /** SP5's falsify bound: below this the fog-detached arm did not see bare basemap. */
    const val BARE_EXPOSURE_MINIMUM_PCT = 30.0

    /**
     * Head-room above the measured floor for label/compression noise while the camera moves.
     *
     * SP5 uses `noiseFloorPct + 0.05` pct-points against a floor measured in the same video seconds
     * earlier; its detected exposures were 30-98% of map area, so the margin's job is only to
     * absorb codec and label jitter. The frames judged here are within a few hundred milliseconds
     * of a floor measured at the same camera through the same readback - the cover rises as soon as
     * the camera leaves the published surround, and covered frames are discharged separately - so
     * the same scale applies. 0.2 is the top of that range, and 15x tighter than the 3.0 the
     * harness carried before it had a caller.
     */
    const val EXPOSURE_MARGIN_PCT = 0.2

    /**
     * A surviving cluster must be twice the largest one the settled floor produced at that camera.
     * Labels and POI glyphs are thin separated strokes; one missing fog tile renders at
     * `FogTilePngCodec.TILE_SIZE` dp square, orders of magnitude above any of them. The
     * [FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX] absolute minimum still applies underneath.
     */
    const val CLUSTER_MARGIN_MULTIPLE = 2.0

    /** Frames the gesture window must contain before any pixel claim is made about it. */
    const val MINIMUM_IN_WINDOW_FRAMES = 4

    /**
     * A covered stretch shorter than this cannot be photographed: `uiAutomation.takeScreenshot`
     * takes appreciably longer than one sampling period, and a capture taken across a cover
     * transition is discarded rather than judged. Below it the trial records that the cover was too
     * brief to corroborate instead of failing on the shutter speed.
     *
     * This is deliberately the ONLY escape from the pixel corroboration, and it is per trial rather
     * than per case. A trial whose cover was up for longer than this and produced no proof fails
     * (COVER_UNCORROBORATED), so the covered half of the contract can never rest on a view tag
     * alone whenever the screen was blanked long enough to photograph. A case-level "at least one
     * trial corroborated" clause on top of that would add nothing except a false red on a product
     * whose covers are all shorter than the shutter - which is the good direction.
     */
    const val COVER_PROOF_MINIMUM_RUN_MILLIS = 400L

    /** True when this frame shows ground that is neither fog nor cover, above its own floor. */
    fun leaks(frame: ExposureFrame, floor: FloorReading): Boolean {
        val clusterThresholdPx = maxOf(
            FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX,
            (floor.largestClusterPx * CLUSTER_MARGIN_MULTIPLE).toInt(),
        )
        return frame.tally.largestClusterPx >= clusterThresholdPx &&
            frame.tally.exposedPct > floor.exposedPct + EXPOSURE_MARGIN_PCT
    }

    /** The same rule applied to the fog-detached reading, which must fire. */
    fun bareLeaks(bare: BareReading, floor: FloorReading): Boolean {
        val clusterThresholdPx = maxOf(
            FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX,
            (floor.largestClusterPx * CLUSTER_MARGIN_MULTIPLE).toInt(),
        )
        return bare.largestClusterPx >= clusterThresholdPx &&
            bare.exposedPct > floor.exposedPct + EXPOSURE_MARGIN_PCT
    }

    /**
     * Every clause the trial has to satisfy, collected so one failure names all of them.
     *
     * Returns the failure lines rather than throwing, so a multi-camera case reports every camera
     * that failed instead of the first.
     */
    fun failuresFor(report: GestureTrialReport, bare: BareReading?): List<String> {
        val line = report.describe()
        val failures = mutableListOf<String>()
        failures += samplerFailures(report, line)
        failures += sensitivityFailures(report, bare, line)
        failures += windowFailures(report, line)
        failures += pixelFailures(report, line)
        failures += movementFailures(report, line)
        failures += coverFailures(report, line)
        return failures
    }

    private fun samplerFailures(report: GestureTrialReport, line: String): List<String> {
        val failure = report.samplerFailure ?: return emptyList()
        return listOf("SAMPLER_DIED - the screen sampler threw $failure: $line")
    }

    private fun sensitivityFailures(
        report: GestureTrialReport,
        bare: BareReading?,
        line: String,
    ): List<String> {
        val failures = mutableListOf<String>()
        if (bare == null) {
            failures += "NO_SENSITIVITY_ARM - no fog-detached reading was measured at this " +
                "trial's own start camera, so its silence proves nothing: $line"
            return failures
        }
        val floor = report.startFloors[bare.channel]
        if (floor == null) {
            failures += "CHANNEL_MISMATCH - the fog-detached arm read channel ${bare.channel} " +
                "where the audited floor has ${report.startFloors.keys}, so floor and frames do " +
                "not come from the same capture channel: $line ${bare.describe()}"
            return failures
        }
        if (
            bare.exposedPct < BARE_EXPOSURE_MINIMUM_PCT ||
            bare.largestClusterPx < FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX
        ) {
            failures += "ORACLE_BLIND - with the fog detached at this trial's own start camera " +
                "the oracle read only ${"%.2f".format(bare.exposedPct)}% of the map as non-fog " +
                "(largest cluster ${bare.largestClusterPx}px), so this run never showed it " +
                "seeing bare basemap here: $line ${bare.describe()}"
        }
        if (!bareLeaks(bare, floor)) {
            failures += "ORACLE_SILENT - the fog-detached reading did not fail this trial's own " +
                "leak rule, so the rule cannot be shown able to see a leak in this run: " +
                "$line ${bare.describe()}"
        }
        if (floor.exposedPct > bare.exposedPct * SETTLED_FLOOR_BARE_FRACTION) {
            failures += "FLOOR_CLOSER_TO_BARE - the settled start camera read " +
                "${"%.2f".format(floor.exposedPct)}% non-fog against a bare reference of " +
                "${"%.2f".format(bare.exposedPct)}%, so this trial never had fully fogged " +
                "never-visited ground under it: $line ${bare.describe()}"
        }
        return failures
    }

    private fun windowFailures(report: GestureTrialReport, line: String): List<String> {
        val failures = mutableListOf<String>()
        val inWindow = report.inWindowFrames
        if (inWindow.size < MINIMUM_IN_WINDOW_FRAMES) {
            failures += "WINDOW_TOO_THIN - only ${inWindow.size} sampled frames fall inside the " +
                "gesture window, under the $MINIMUM_IN_WINDOW_FRAMES a pixel claim about the " +
                "gesture needs; the frames after it cannot stand in for them: $line"
        }
        val unjudged = inWindow.filter { it.channel == GestureExposurePixels.UNJUDGED_CHANNEL }
        if (unjudged.isNotEmpty()) {
            failures += "UNJUDGED_IN_WINDOW - ${unjudged.size} frames inside the gesture window " +
                "produced no capture at all. An unjudged frame is an unanswered question about " +
                "what the user saw, so it fails rather than being filtered away: " +
                unjudged.take(3).joinToString(" ") { it.describe() } + " $line"
        }
        return failures
    }

    private fun pixelFailures(report: GestureTrialReport, line: String): List<String> {
        val failures = mutableListOf<String>()
        val judged = (report.inWindowFrames + report.afterWindowFrames)
            .filter { frame -> frame.judged && !frame.coverUp }
        val orphaned = judged.filter { frame -> report.floorFor(frame) == null }
        if (orphaned.isNotEmpty()) {
            failures += "NO_FLOOR_FOR_CHANNEL - ${orphaned.size} judged frames arrived on a " +
                "channel with no settled floor measured through it " +
                "(${orphaned.map { it.channel }.distinct()}): $line"
        }
        val leaking = judged.filter { frame ->
            val floor = report.floorFor(frame) ?: return@filter false
            leaks(frame, floor)
        }
        if (leaking.isNotEmpty()) {
            failures += "BASEMAP_EXPOSED - ${leaking.size} sampled frames were neither the " +
                "opaque safety cover nor fog over unexplored ground: " +
                leaking.take(3).joinToString(" ") { frame ->
                    frame.describe() + " floor=[" + report.floorFor(frame)?.describe() + "]"
                } + " $line"
        }
        if (report.frames.lastOrNull()?.coverUp == true) {
            failures += "COVER_NEVER_FELL - the safety cover was still up when the trial ended, " +
                "so the map never came back: $line"
        }
        return failures
    }

    private fun movementFailures(report: GestureTrialReport, line: String): List<String> {
        val failures = mutableListOf<String>()
        val kind = report.kind
        kind.minimumZoomOut?.let { minimum ->
            if (report.zoomDelta > -minimum) {
                failures += "GESTURE_SWALLOWED - the camera zoomed out by " +
                    "${"%.3f".format(-report.zoomDelta)} levels, under the $minimum this kind " +
                    "must achieve, so nothing about a zoom-out was measured: $line"
            }
        }
        kind.minimumZoomIn?.let { minimum ->
            if (report.zoomDelta < minimum) {
                failures += "GESTURE_SWALLOWED - the camera zoomed in by " +
                    "${"%.3f".format(report.zoomDelta)} levels, under the $minimum this kind " +
                    "must achieve: $line"
            }
        }
        kind.minimumTiltDegrees?.let { minimum ->
            if (abs(report.tiltDelta) < minimum) {
                failures += "GESTURE_SWALLOWED - the camera tilted by " +
                    "${"%.2f".format(abs(report.tiltDelta))} degrees, under the $minimum this " +
                    "kind must achieve: $line"
            }
        }
        kind.minimumBearingDegrees?.let { minimum ->
            if (abs(report.bearingDelta) < minimum) {
                failures += "GESTURE_SWALLOWED - the camera turned by " +
                    "${"%.2f".format(abs(report.bearingDelta))} degrees, under the $minimum this " +
                    "kind must achieve: $line"
            }
        }
        if (report.touchDownGrowth != report.drive.injectedDownCount) {
            failures += "GESTURE_NEVER_REACHED_THE_MAP - map_touch_down_count grew by " +
                "${report.touchDownGrowth} for ${report.drive.injectedDownCount} injected " +
                "ACTION_DOWNs, so the stream did not reach the gesture-owning MapView intact: $line"
        }
        return failures
    }

    private fun coverFailures(report: GestureTrialReport, line: String): List<String> {
        val failures = mutableListOf<String>()
        if (report.kind.requiresCoverToRise && !report.coverRose) {
            failures += "COVER_NEVER_ROSE - this gesture leaves the published surround, so the " +
                "opaque cover must have been raised at some sampled frame; without it every " +
                "cover clause below would be skipped rather than satisfied: $line"
        }
        if (!report.coverRose) return failures
        if (report.longestCoveredRunMillis > COVER_INTERVAL_BOUND_MILLIS) {
            failures += "COVER_TOO_LONG - the map stayed blanked by the safety cover for " +
                "${report.longestCoveredRunMillis} ms across consecutive sampled frames, past " +
                "the $COVER_INTERVAL_BOUND_MILLIS ms bound: $line"
        }
        val published = report.coverIntervalAfterMillis
        if (published == null) {
            failures += "NO_PUBLISHED_INTERVAL - the surface published no cover interval for a " +
                "gesture that raised the cover: $line"
        } else {
            if (published == report.coverIntervalBeforeMillis) {
                failures += "STALE_PUBLISHED_INTERVAL - map_fog_last_cover_interval_ms still " +
                    "reads ${published} ms, the value it held before the gesture, so the bound " +
                    "below would be applied to a retained measurement of an earlier cover: $line"
            }
            if (published > COVER_INTERVAL_BOUND_MILLIS) {
                failures += "COVER_TOO_LONG - the published cover interval ${published} ms " +
                    "exceeded the $COVER_INTERVAL_BOUND_MILLIS ms bound: $line"
            }
        }
        val proof = report.coverPixelProof
        if (proof == null) {
            if (report.longestCoveredRunMillis >= COVER_PROOF_MINIMUM_RUN_MILLIS) {
                failures += "COVER_UNCORROBORATED - the cover was up for " +
                    "${report.longestCoveredRunMillis} ms and " +
                    "${report.coverProofAttempts} screen-truth attempts recorded nothing, so the " +
                    "covered half of the contract would rest on a view tag alone: $line"
            }
        } else if (proof.mismatched != 0) {
            failures += "COVER_DID_NOT_DRAW - ${proof.mismatched} of ${proof.samples} screen " +
                "samples taken while the cover tag was true were not opaque cover pixels; a " +
                "cover that silently fails to draw reads as protection everywhere else: " +
                "${proof.describe()} $line"
        }
        return failures
    }

    fun emit(report: GestureTrialReport, bare: BareReading?) {
        SpikeEvidence.emit(
            InstrumentationRegistry.getInstrumentation().targetContext,
            EVIDENCE_FILE,
            "TRAILVEIL-V02007-GESTURE ${report.describe()} " +
                (bare?.describe() ?: "bare=none"),
        )
    }

    fun emitSummary(label: String, trials: Int, failures: List<String>, proofs: Int) {
        SpikeEvidence.emit(
            InstrumentationRegistry.getInstrumentation().targetContext,
            EVIDENCE_FILE,
            "TRAILVEIL-V02007-GESTURE-SUMMARY case=$label trials=$trials " +
                "api=${android.os.Build.VERSION.SDK_INT} product=${android.os.Build.PRODUCT} " +
                "coverPixelProofs=$proofs failures=${failures.size}",
        )
    }
}
