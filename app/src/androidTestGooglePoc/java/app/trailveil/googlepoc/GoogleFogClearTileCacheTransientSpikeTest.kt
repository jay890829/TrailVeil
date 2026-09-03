package app.trailveil.googlepoc

import android.graphics.Bitmap
import android.graphics.Point
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.GoogleMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 3, SP9 — the grafted linchpin: between `clearTileCache()` and the PROVEN
 * repaint of the new generation, does the renderer ever present a non-fog frame over a fully
 * fogged, label-dense viewport? The verdict window is [t_clear, t_installProven] (barrier drain
 * only marks bytes leaving the provider; compositing happens after), sub-bucketed for diagnosis.
 * Zero non-fog frames across all same-palette windows permits single-overlay refresh
 * `[refinable]`; any non-fog frame confirms dual-overlay add-before-remove as the mandatory
 * default. Rotation windows are separate evidence and never flip the refresh verdict.
 *
 * Opt-in: `trailveilGoogleFogClearTransient=true`; `trailveilGoogleFogRendererPreference`
 * (default latest).
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogClearTileCacheTransientSpikeTest {

    /** Per-cycle event timestamps written by the transient observer (lock-free). */
    private class CycleWindow {
        val clearAt = AtomicLong(0L)
        val rotationRemoveAt = AtomicLong(0L)
        val rotationAttachAt = AtomicLong(0L)
        val drainedAt = AtomicLong(0L)
        val provenAt = AtomicLong(0L)
        val deliveries = AtomicInteger(0)
        val snapshotAttempt = AtomicInteger(0)
        val generation = AtomicLong(0L)

        fun firstEvent(): Long {
            val rotation = rotationRemoveAt.get()
            val clear = clearAt.get()
            return when {
                rotation != 0L && clear != 0L -> minOf(rotation, clear)
                rotation != 0L -> rotation
                else -> clear
            }
        }

        fun reset() {
            clearAt.set(0L)
            rotationRemoveAt.set(0L)
            rotationAttachAt.set(0L)
            drainedAt.set(0L)
            provenAt.set(0L)
            deliveries.set(0)
            snapshotAttempt.set(0)
            generation.set(0L)
        }
    }

    private data class SampleRecord(
        val requestNanos: Long,
        val callbackNanos: Long,
        val nonFogProbes: Int,
        val stream: String,
    )

    @Test
    fun clearTileCacheRepaintTransientOverLabelDenseFoggedViewport() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilGoogleFogClearTransient")
        SpikeScenarioSupport.assumeKeyConfigured()
        SpikeScenarioSupport.assumeEmptyCanonicalTables()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requested = InstrumentationRegistry.getArguments()
            .getString("trailveilGoogleFogRendererPreference") ?: "latest"
        val renderer = GoogleRendererPin.initialize(context, requested)

        val installLatch = AtomicReference(CountDownLatch(1))
        val scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCanonicalFogInstalled(generation: Long) {
                        installLatch.get().countDown()
                    }
                }
            }
            val mapView = SpikeScenarioSupport.awaitMapView(scenario)
            val map = SpikeScenarioSupport.awaitGoogleMap(scenario, mapView)
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            scenario.onActivity { it.setStatusOverlaySuppressedForTesting(true) }
            val activity = SpikeScenarioSupport.requireActivity(scenario)

            val renderSurface = with(SpikeScenarioSupport) { mapView.findRenderSurface() }
            val surfaceClass = renderSurface?.javaClass?.simpleName ?: "none"
            val pixelCopyAlive = renderSurface is SurfaceView || renderSurface is TextureView

            // Steady-state calibration over a 20x20 grid: watermark/legal/compass exclusions.
            val grid = buildGrid(mapView.width, mapView.height)
            val excluded = BooleanArray(grid.size)
            val excludedByPc = BooleanArray(grid.size)
            val excludedBySnap = BooleanArray(grid.size)
            val excludedSampleRgb = mutableListOf<String>()
            var anchorGeneration = awaitInstalled(scenario)
            // 5 rounds: POI labels fade in seconds after idle; the union must catch them all.
            // On the LATEST renderer basemap labels draw ABOVE the opaque fog TileOverlay in the
            // live surface (measured: 49/400 grid points, horizontal glyph runs + POI icons),
            // while snapshot() composites them below — so the PixelCopy channel NEEDS these
            // exclusions and the bound is a sensitivity floor, not a label-count assumption.
            repeat(5) {
                captureSurface(renderSurface, mapView)?.let { bitmap ->
                    grid.forEachIndexed { index, point ->
                        if (!excluded[index] && isNonFog(bitmap, point, anchorGeneration)) {
                            excluded[index] = true
                            excludedByPc[index] = true
                            if (excludedSampleRgb.size < 12) {
                                val pixel = bitmap[
                                    point.x.coerceIn(0, bitmap.width - 1),
                                    point.y.coerceIn(0, bitmap.height - 1),
                                ]
                                excludedSampleRgb += "pc(${point.x},${point.y})=" +
                                    "${android.graphics.Color.red(pixel)}," +
                                    "${android.graphics.Color.green(pixel)}," +
                                    "${android.graphics.Color.blue(pixel)}," +
                                    "a${android.graphics.Color.alpha(pixel)}"
                            }
                        }
                    }
                    bitmap.recycle()
                }
                snapshotBitmap(scenario, map)?.let { bitmap ->
                    grid.forEachIndexed { index, point ->
                        if (!excluded[index] && isNonFog(bitmap, point, anchorGeneration)) {
                            excluded[index] = true
                            excludedBySnap[index] = true
                            if (excludedSampleRgb.size < 12) {
                                val pixel = bitmap[
                                    point.x.coerceIn(0, bitmap.width - 1),
                                    point.y.coerceIn(0, bitmap.height - 1),
                                ]
                                excludedSampleRgb += "snap(${point.x},${point.y})=" +
                                    "${android.graphics.Color.red(pixel)}," +
                                    "${android.graphics.Color.green(pixel)}," +
                                    "${android.graphics.Color.blue(pixel)}," +
                                    "a${android.graphics.Color.alpha(pixel)}"
                            }
                        }
                    }
                    bitmap.recycle()
                }
                SystemClock.sleep(300L)
            }
            val calibExcluded = excluded.count { it }
            if (calibExcluded > MAX_CALIBRATION_EXCLUSIONS) {
                // Diagnostic dump before the INVALID gate: mask pattern + first samples. Screen
                // space only — no world coordinates.
                SpikeEvidence.emit(
                    context,
                    "sp9-calibration-diag.txt",
                    "TrailVeil SP9 calibDiag mapView=${mapView.width}x${mapView.height} " +
                        "surface=$surfaceClass pcExcl=${excludedByPc.count { it }} " +
                        "snapExcl=${excludedBySnap.count { it }} " +
                        "samples=${excludedSampleRgb.joinToString(";")}",
                )
                for (row in 0 until 20) {
                    val mask = buildString {
                        for (column in 0 until 20) {
                            val index = row * 20 + column
                            append(
                                when {
                                    excludedByPc[index] && excludedBySnap[index] -> 'B'
                                    excludedByPc[index] -> 'P'
                                    excludedBySnap[index] -> 'S'
                                    else -> '.'
                                },
                            )
                        }
                    }
                    SpikeEvidence.emit(context, "sp9-calibration-diag.txt", "TrailVeil SP9 calibMask row${"%02d".format(row)} $mask")
                }
            }
            assertTrue(
                "SP9 INVALID: calibration excluded $calibExcluded/400 grid points",
                calibExcluded <= MAX_CALIBRATION_EXCLUSIONS,
            )
            assertTrue(
                "SP9 INVALID: only ${400 - calibExcluded} active probes (< $MIN_ACTIVE_PROBES)",
                400 - calibExcluded >= MIN_ACTIVE_PROBES,
            )

            // Transient observer: lock-free timestamp capture (the clear emit site runs outside
            // the phase coordinator's lock, but the observer still never calls back into the
            // controller).
            val window = CycleWindow()
            scenario.onActivity {
                it.fogOverlayControllerForTesting()?.setRefreshTransientObserverForTesting(
                    object : GoogleFogRefreshTransientObserver {
                        override fun onClearTileCacheIssued(generation: Long, atNanos: Long) {
                            window.clearAt.compareAndSet(0L, atNanos)
                            window.generation.set(generation)
                        }

                        override fun onRotationOverlayRemoved(generation: Long, atNanos: Long) {
                            window.rotationRemoveAt.compareAndSet(0L, atNanos)
                        }

                        override fun onRotationOverlayAttached(generation: Long, atNanos: Long) {
                            window.rotationAttachAt.compareAndSet(0L, atNanos)
                        }

                        override fun onCanonicalTileDelivered(generation: Long, atNanos: Long) {
                            window.deliveries.incrementAndGet()
                        }

                        override fun onDeliveryBarrierDrained(generation: Long, atNanos: Long) {
                            window.drainedAt.compareAndSet(0L, atNanos)
                        }

                        override fun onInstallProven(
                            generation: Long,
                            atNanos: Long,
                            snapshotAttempt: Int,
                        ) {
                            window.provenAt.compareAndSet(0L, atNanos)
                            window.snapshotAttempt.set(snapshotAttempt)
                        }
                    },
                )
            }

            // Samplers.
            val samples = java.util.concurrent.ConcurrentLinkedQueue<SampleRecord>()
            val running = AtomicBoolean(true)
            val burstActive = AtomicBoolean(false)
            val suspendSnapshots = AtomicBoolean(false)
            val framesPresented = AtomicInteger(0)
            val snapshotNulls = AtomicInteger(0)

            // OTHER classification is generation-independent (stale palette colors resolve via
            // the palette-multiple check), so samplers read a driver-owned cache instead of a
            // per-sample onActivity round-trip that would block behind the busy install-time
            // main thread.
            val samplerGeneration = AtomicLong(anchorGeneration)

            // Sleep in short slices so a newly armed burst engages within ~2 ms instead of after
            // a full baseline period — the verdict window's P50 is only ~117 ms.
            fun slicedSleep(totalMillis: Long, wakeOnBurst: Boolean) {
                val deadline = SystemClock.elapsedRealtime() + totalMillis
                val wasBurst = burstActive.get()
                while (running.get() && SystemClock.elapsedRealtime() < deadline) {
                    if (wakeOnBurst && !wasBurst && burstActive.get()) return
                    SystemClock.sleep(2L)
                }
            }

            val snapshotThread = Thread {
                while (running.get()) {
                    if (!suspendSnapshots.get()) {
                        val request = SystemClock.elapsedRealtimeNanos()
                        val bitmap = snapshotBitmap(scenario, map)
                        val callback = SystemClock.elapsedRealtimeNanos()
                        if (bitmap == null) {
                            snapshotNulls.incrementAndGet()
                        } else {
                            samples.add(
                                SampleRecord(
                                    request,
                                    callback,
                                    countNonFog(bitmap, grid, excluded, samplerGeneration.get()),
                                    "snap",
                                ),
                            )
                            bitmap.recycle()
                        }
                    }
                    slicedSleep(SNAPSHOT_PERIOD_MILLIS, wakeOnBurst = true)
                }
            }.also(Thread::start)

            val pcThread = Thread {
                while (running.get()) {
                    if (pixelCopyAlive) {
                        val request = SystemClock.elapsedRealtimeNanos()
                        val bitmap = captureSurface(renderSurface, mapView)
                        val callback = SystemClock.elapsedRealtimeNanos()
                        if (bitmap != null) {
                            samples.add(
                                SampleRecord(
                                    request,
                                    callback,
                                    countNonFog(bitmap, grid, excluded, samplerGeneration.get()),
                                    "pc",
                                ),
                            )
                            bitmap.recycle()
                        }
                    }
                    val period = if (burstActive.get()) {
                        if (renderSurface is TextureView) TEXTURE_BURST_PERIOD_MILLIS else PC_BURST_PERIOD_MILLIS
                    } else {
                        PC_BASELINE_PERIOD_MILLIS
                    }
                    slicedSleep(period, wakeOnBurst = true)
                }
            }.also(Thread::start)

            // Frame counter: framesPresented between window start and proven (main thread).
            val frameCallback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (burstActive.get()) framesPresented.incrementAndGet()
                    if (running.get()) Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Handler(Looper.getMainLooper()).post {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }

            // ---- Cycle driver ----
            data class CycleRow(
                val index: Int,
                val intendedRotation: Boolean,
                val observedRotation: Boolean,
                val burns: Int,
                val windowMs: Long,
                val drainMs: Long,
                val tileRequests: Int,
                val frames: Int,
                val pcInWindow: Int,
                val snapInWindow: Int,
                val nonFogSamePalette: Int,
                val nonFogRotation: Int,
                val maxNonFogProbes: Int,
                val pcNonFog: Int,
                val snapNonFog: Int,
            )

            val rows = mutableListOf<CycleRow>()
            var steadyNonFog = 0
            var previousCycleEnd = SystemClock.elapsedRealtimeNanos()
            val totalCycles = SAME_PALETTE_CYCLES + ROTATION_CYCLES
            for (cycle in 1..totalCycles) {
                val intendedRotation = cycle > SAME_PALETTE_CYCLES
                // Steady-state check between cycles: any non-fog sample since last proven+settle
                // is SP1-territory INVALID, counted before the new window opens.
                steadyNonFog += samples.filter {
                    it.requestNanos > previousCycleEnd && it.nonFogProbes > 0
                }.size

                samplerGeneration.set(anchorGeneration)
                var burns = 0
                if (intendedRotation) {
                    val burned = AtomicInteger(-2)
                    scenario.onActivity {
                        burned.set(it.advanceToNextPaletteCycleBoundaryForTesting())
                    }
                    burns = burned.get()
                    assertTrue("SP9 palette-boundary burn failed ($burns)", burns >= 0)
                }
                // Reset AFTER the burn loop: burn refreshes emit their own clear events, and a
                // pre-burn reset would open the window ~1 s early and contaminate rotation rows.
                window.reset()
                installLatch.set(CountDownLatch(1))
                framesPresented.set(0)
                val started = AtomicBoolean(false)
                scenario.onActivity { started.set(it.requestCanonicalFogRefreshForTesting()) }
                assertTrue("SP9 refresh trigger rejected at cycle $cycle", started.get())
                // Arm the burst from the first observer event.
                val burstArmDeadline = SystemClock.elapsedRealtime() + 5_000L
                while (window.firstEvent() == 0L &&
                    SystemClock.elapsedRealtime() < burstArmDeadline
                ) {
                    SystemClock.sleep(2L)
                }
                burstActive.set(true)
                if (pixelCopyAlive) suspendSnapshots.set(false)
                val installed = installLatch.get().await(30, TimeUnit.SECONDS)
                burstActive.set(false)
                suspendSnapshots.set(false)
                if (!installed) {
                    val diagnostic = AtomicReference<GoogleFogInstallDiagnostic>()
                    scenario.onActivity { diagnostic.set(it.fogInstallDiagnosticForTesting()) }
                    error("SP9 cycle $cycle never installed: ${diagnostic.get()}")
                }
                SystemClock.sleep(SETTLE_MILLIS)
                anchorGeneration = awaitInstalled(scenario)

                val tClear = window.clearAt.get()
                val tRotRemove = window.rotationRemoveAt.get()
                val tProven = window.provenAt.get()
                val tDrained = window.drainedAt.get()
                val windowStart = window.firstEvent()
                val observedRotation = tRotRemove != 0L
                val inWindow = samples.filter {
                    it.requestNanos >= windowStart && it.callbackNanos <= tProven
                }
                val nonFogInWindow = inWindow.count { it.nonFogProbes > 0 }
                val row = CycleRow(
                    index = cycle,
                    intendedRotation = intendedRotation,
                    observedRotation = observedRotation,
                    burns = burns,
                    windowMs = if (tProven > windowStart) (tProven - windowStart) / 1_000_000L else -1L,
                    drainMs = if (tDrained > windowStart) (tDrained - windowStart) / 1_000_000L else -1L,
                    tileRequests = window.deliveries.get(),
                    frames = framesPresented.get(),
                    pcInWindow = inWindow.count { it.stream == "pc" },
                    snapInWindow = inWindow.count { it.stream == "snap" },
                    nonFogSamePalette = if (observedRotation) 0 else nonFogInWindow,
                    nonFogRotation = if (observedRotation) nonFogInWindow else 0,
                    maxNonFogProbes = inWindow.maxOfOrNull { it.nonFogProbes } ?: 0,
                    pcNonFog = inWindow.count { it.stream == "pc" && it.nonFogProbes > 0 },
                    snapNonFog = inWindow.count { it.stream == "snap" && it.nonFogProbes > 0 },
                )
                rows += row
                SpikeEvidence.emit(
                    context,
                    "sp9-cycles.txt",
                    "TrailVeil SP9 cycle=${row.index} kind=${if (row.observedRotation) "rotation" else "same"} " +
                        "burns=${row.burns} windowMs=${row.windowMs} drainMs=${row.drainMs} " +
                        "tileReq=${row.tileRequests} frames=${row.frames} pcInWindow=${row.pcInWindow} " +
                        "snapInWindow=${row.snapInWindow} nonFogSamples=$nonFogInWindow " +
                        "maxNonFogProbes=${row.maxNonFogProbes} pcNonFog=${row.pcNonFog} " +
                        "snapNonFog=${row.snapNonFog}",
                )
                assertTrue(
                    "SP9 cycle $cycle type mismatch: intendedRotation=$intendedRotation observed=$observedRotation",
                    intendedRotation == observedRotation,
                )
                previousCycleEnd = tProven + SETTLE_MILLIS * 1_000_000L
                samples.clear()
            }

            running.set(false)
            snapshotThread.join(3_000L)
            pcThread.join(3_000L)

            // ---- Falsification: detached overlay must expose the label-dense basemap ----
            scenario.onActivity {
                it.fogOverlayControllerForTesting()?.setRefreshTransientObserverForTesting(null)
                it.fogOverlayControllerForTesting()?.detach()
            }
            SystemClock.sleep(1_500L)
            var falsifySnapMax = 0.0
            var falsifyPcMax = 0.0
            val activeProbes = excluded.count { !it }
            repeat(3) {
                snapshotBitmap(scenario, map)?.let { bitmap ->
                    val nonFog = countNonFog(bitmap, grid, excluded, anchorGeneration)
                    falsifySnapMax = maxOf(falsifySnapMax, nonFog * 100.0 / activeProbes)
                    bitmap.recycle()
                }
                if (pixelCopyAlive) {
                    captureSurface(renderSurface, mapView)?.let { bitmap ->
                        val nonFog = countNonFog(bitmap, grid, excluded, anchorGeneration)
                        falsifyPcMax = maxOf(falsifyPcMax, nonFog * 100.0 / activeProbes)
                        bitmap.recycle()
                    }
                }
                SystemClock.sleep(400L)
            }

            // ---- Scoring ----
            val samePaletteRows = rows.filter { !it.observedRotation }
            val rotationRows = rows.filter { it.observedRotation }
            val unobserved = samePaletteRows.count { row ->
                val needed = if (pixelCopyAlive) (row.frames + 1) / 2 else 1
                val got = if (pixelCopyAlive) row.pcInWindow else row.snapInWindow
                got < needed
            }
            val nonFogSame = samePaletteRows.sumOf { it.nonFogSamePalette }
            val nonFogRotation = rotationRows.sumOf { it.nonFogRotation }
            val falsifyPass = falsifySnapMax >= FALSIFY_MINIMUM_PCT &&
                (!pixelCopyAlive || falsifyPcMax >= FALSIFY_MINIMUM_PCT)
            // Detection outranks coverage: a caught non-fog frame is a positive answer no
            // matter how many windows were under-sampled. Coverage only gates the ZERO-transient
            // (PASS) claim.
            val verdict = when {
                !falsifyPass || steadyNonFog > 0 -> "INVALID"
                nonFogSame > 0 -> "FAIL"
                unobserved > 0 -> "INDETERMINATE"
                else -> "PASS"
            }
            val windowsSorted = rows.map { it.windowMs }.sorted()

            val line = "TrailVeil SP9 clearTileCache-transient ${renderer.asEvidenceTokens()} " +
                "api=${android.os.Build.VERSION.SDK_INT} image=${android.os.Build.PRODUCT} " +
                "surface=$surfaceClass pixelCopyAlive=$pixelCopyAlive " +
                "cycles=$totalCycles samePalette=${samePaletteRows.size} rotation=${rotationRows.size} " +
                "burnsTotal=${rows.sumOf { it.burns }} " +
                "windowMsP50=${percentile(windowsSorted, 50)} windowMsMax=${windowsSorted.maxOrNull()} " +
                "drainMsP50=${percentile(rows.map { it.drainMs }.sorted(), 50)} " +
                "tileReqPerCycleP50=${percentile(rows.map { it.tileRequests.toLong() }.sorted(), 50)} " +
                "framesPerWindowP50=${percentile(rows.map { it.frames.toLong() }.sorted(), 50)} " +
                "pcInWindowMin=${samePaletteRows.minOfOrNull { it.pcInWindow }} " +
                "snapInWindowMin=${samePaletteRows.minOfOrNull { it.snapInWindow }} " +
                "snapNulls=${snapshotNulls.get()} unobservedSamePalette=$unobserved " +
                "nonFogSamePalette=$nonFogSame nonFogRotation=$nonFogRotation " +
                "nonFogPcSame=${samePaletteRows.sumOf { it.pcNonFog }} " +
                "nonFogSnapSame=${samePaletteRows.sumOf { it.snapNonFog }} " +
                "nonFogPcRotation=${rotationRows.sumOf { it.pcNonFog }} " +
                "nonFogSnapRotation=${rotationRows.sumOf { it.snapNonFog }} " +
                "nonFogSteady=$steadyNonFog calibExcluded=$calibExcluded/400 " +
                "falsifyNonFogPct=snap:${"%.1f".format(falsifySnapMax)}/pc:${"%.1f".format(falsifyPcMax)} " +
                "verdict=$verdict singleOverlayRefreshPermitted=${verdict == "PASS"} " +
                "engineeringEvidenceOnly"
            SpikeEvidence.emit(context, "sp9-clear-transient.txt", line)
            assertTrue("SP9 $verdict: $line", verdict == "PASS" || verdict == "FAIL")
        } finally {
            scenario.close()
        }
    }

    private fun buildGrid(width: Int, height: Int): List<Point> = buildList {
        val border = 8
        for (row in 0 until 20) {
            for (column in 0 until 20) {
                add(
                    Point(
                        border + column * (width - 2 * border) / 19,
                        border + row * (height - 2 * border) / 19,
                    ),
                )
            }
        }
    }

    private fun isNonFog(bitmap: Bitmap, point: Point, generation: Long): Boolean {
        val x = point.x.coerceIn(0, bitmap.width - 1)
        val y = point.y.coerceIn(0, bitmap.height - 1)
        return GoogleFogSpikePixelClassifier.classify(bitmap[x, y], generation) ==
            GoogleFogSpikePixelClass.OTHER
    }

    private fun countNonFog(
        bitmap: Bitmap,
        grid: List<Point>,
        excluded: BooleanArray,
        generation: Long,
    ): Int {
        var count = 0
        grid.forEachIndexed { index, point ->
            if (!excluded[index] && isNonFog(bitmap, point, generation)) count += 1
        }
        return count
    }

    private fun currentGeneration(scenario: ActivityScenario<GoogleMapsPocActivity>): Long {
        val generation = AtomicReference(1L)
        scenario.onActivity {
            it.installedFogGenerationForTesting()?.let(generation::set)
        }
        return generation.get()
    }

    private fun awaitInstalled(scenario: ActivityScenario<GoogleMapsPocActivity>): Long {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val generation = AtomicReference<Long?>()
            scenario.onActivity { generation.set(it.installedFogGenerationForTesting()) }
            generation.get()?.let { return it }
            SystemClock.sleep(100L)
        }
        error("SP9 no installed generation available")
    }

    private val captureHandlerThread by lazy {
        HandlerThread("sp9-pixelcopy").also(HandlerThread::start)
    }

    /** Surface-source readback: never the window overload (it would see the opaque cover). */
    private fun captureSurface(surface: android.view.View?, mapView: android.view.View): Bitmap? {
        return when (surface) {
            is SurfaceView -> {
                if (surface.width <= 0 || surface.height <= 0) return null
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
                        Handler(captureHandlerThread.looper),
                    )
                } catch (_: Exception) {
                    bitmap.recycle()
                    return null
                }
                latch.await(2, TimeUnit.SECONDS)
                if (status == PixelCopy.SUCCESS) bitmap else null.also { bitmap.recycle() }
            }
            is TextureView -> {
                val holder = AtomicReference<Bitmap?>()
                val latch = CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    holder.set(
                        try {
                            surface.getBitmap(
                                createBitmap(
                                    surface.width.coerceAtLeast(1),
                                    surface.height.coerceAtLeast(1),
                                ),
                            )
                        } catch (_: Exception) {
                            null
                        },
                    )
                    latch.countDown()
                }
                latch.await(2, TimeUnit.SECONDS)
                holder.get()
            }
            else -> null
        }
    }

    private fun snapshotBitmap(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: GoogleMap,
    ): Bitmap? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Bitmap?>()
        try {
            scenario.onActivity {
                try {
                    map.snapshot { bitmap ->
                        holder.set(bitmap)
                        latch.countDown()
                    }
                } catch (_: Exception) {
                    latch.countDown()
                }
            }
        } catch (_: Exception) {
            return null
        }
        return if (latch.await(5, TimeUnit.SECONDS)) holder.get() else null
    }

    private fun percentile(sorted: List<Long>, percent: Int): Long {
        if (sorted.isEmpty()) return -1L
        val index = ((sorted.size * percent) / 100).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private companion object {
        const val SAME_PALETTE_CYCLES = 20
        const val ROTATION_CYCLES = 10
        const val MAX_CALIBRATION_EXCLUSIONS = 100
        const val MIN_ACTIVE_PROBES = 300
        const val SETTLE_MILLIS = 500L
        const val SNAPSHOT_PERIOD_MILLIS = 100L
        const val PC_BASELINE_PERIOD_MILLIS = 100L
        const val PC_BURST_PERIOD_MILLIS = 16L
        const val TEXTURE_BURST_PERIOD_MILLIS = 50L
        const val FALSIFY_MINIMUM_PCT = 5.0
    }
}
