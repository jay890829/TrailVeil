package app.trailveil.googlepoc

import android.graphics.Point
import android.os.SystemClock
import androidx.core.graphics.get
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.benchmark.ScaleBenchmarkFixture
import app.trailveil.data.db.TrailVeilDatabase
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 3, SP6: across 20 onStop/onStart cycles with a proven canonical generation
 * installed — does the SDK re-request tiles (counter deltas, read passively at three points, no
 * sampling blackout), what is time-to-repaint-proof, and does any sampled frame show a
 * stale-palette or placeholder pixel at a point where canonical fog was proven? The restart
 * window (including 0–500 ms) is observed with screen captures, which drive no map render pass
 * and issue no tile requests, so the SDK-initiated counter stays clean while being watched.
 *
 * Opt-in: `trailveilGoogleStopStartSpike=true`; `trailveilGoogleRenderer` (default latest).
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogStopStartCacheSpikeTest {

    @Test
    fun stopStartCyclesPreserveCanonicalTilePresentation() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilGoogleStopStartSpike")
        SpikeScenarioSupport.assumeKeyConfigured()
        SpikeScenarioSupport.assumeEmptyCanonicalTables()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requested = InstrumentationRegistry.getArguments()
            .getString("trailveilGoogleRenderer") ?: "latest"
        val renderer = GoogleRendererPin.initialize(context, requested)

        val database = TrailVeilDatabase.open(context)
        try {
            ScaleBenchmarkFixture.populateCanonicalDataset(database, POINT_COUNT)
        } finally {
            database.close()
        }

        val installLatch = AtomicReference(CountDownLatch(1))
        val installedGeneration = AtomicLong(0L)
        val scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCanonicalFogInstalled(generation: Long) {
                        installedGeneration.set(generation)
                        installLatch.get().countDown()
                    }
                }
            }
            val mapView = SpikeScenarioSupport.awaitMapView(scenario)
            SpikeScenarioSupport.awaitGoogleMap(scenario, mapView)
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            assertTrue(
                "SP6 baseline canonical install never reported",
                installedGeneration.get() != 0L || installLatch.get().await(30, TimeUnit.SECONDS),
            )
            scenario.onActivity { it.setStatusOverlaySuppressedForTesting(true) }

            // Baseline oracle sanity: the installed generation must prove once before cycling.
            var anchorGeneration = awaitInstalledGeneration(scenario)
            val baseline = probeOnce(scenario)
            assertTrue(
                "SP6 baseline snapshot probe did not prove the installed generation: $baseline",
                baseline?.proven == true,
            )

            // Fixed probe grid + steady-state calibration exclusions (watermark, compass, legal
            // text, and the fixture's legitimately explored holes — the camera never moves, so
            // exclusions computed once stay valid for the whole run).
            val activity = SpikeScenarioSupport.requireActivity(scenario)
            val grid = buildGrid(mapView.width, mapView.height)
            val excluded = BooleanArray(grid.size)
            repeat(CALIBRATION_FRAMES) {
                val capture = SpikeScenarioSupport.captureMapView(activity, mapView)
                    ?: error("SP6 calibration capture failed")
                grid.forEachIndexed { index, point ->
                    if (!excluded[index] && classify(capture, point, anchorGeneration) !=
                        GoogleFogSpikePixelClass.MATCH
                    ) {
                        excluded[index] = true
                    }
                }
                capture.bitmap.recycle()
                SystemClock.sleep(200L)
            }
            val activeProbes = excluded.count { !it }
            assertTrue(
                "SP6 calibration excluded too much of the grid ($activeProbes active)",
                activeProbes >= MINIMUM_ACTIVE_PROBES,
            )
            scenario.onActivity { it.fogTileProviderForTesting()?.resetRequestCountersForTesting() }

            var validCycles = 0
            var regeneratedCycles = 0
            var invalidCycles = 0
            var reRequestCycles = 0
            var proofTimeouts = 0
            var notInstalledSamples = 0
            var droppedSnapshots = 0
            var snapshotSamples = 0
            var staleSamples = 0
            var basemapLeakSamples = 0
            var placeholderDuringRepaint = 0
            var postProofDefects = 0
            var captureFailures = 0
            val sdkTileDeltas = mutableListOf<Long>()
            val sdkPlaceholderDeltas = mutableListOf<Long>()
            val totalDeltas = mutableListOf<Long>()
            val proofTimes = mutableListOf<Long>()
            var captureMethod = "unknown"

            var leakCycles = 0
            for (cycle in 1..CYCLES) {
                var cycleLeakSamples = 0
                val identityBefore = activityIdentity(scenario)
                scenario.moveToState(Lifecycle.State.CREATED)
                SystemClock.sleep(STOP_SETTLE_MILLIS)
                installLatch.set(CountDownLatch(1))
                val resumeStart = SystemClock.elapsedRealtimeNanos()
                scenario.moveToState(Lifecycle.State.RESUMED)
                val countersAtResume = readCounters(scenario)

                // Restart-window screen sampling: covers 0 ms onward, requests nothing.
                var proofAtMs = -1L
                var sawProven = false
                val windowDeadline = SystemClock.elapsedRealtime() + PROOF_BOUND_MILLIS
                var countersAtProof: GoogleFogTileRequestCounters? = null
                while (SystemClock.elapsedRealtime() < windowDeadline && !sawProven) {
                    val liveActivity = SpikeScenarioSupport.requireActivity(scenario)
                    val liveMapView = SpikeScenarioSupport.readSurface(scenario).mapView
                    if (liveMapView != null) {
                        val capture = SpikeScenarioSupport.captureMapView(liveActivity, liveMapView)
                        if (capture != null) {
                            captureMethod = capture.method
                            grid.forEachIndexed { index, point ->
                                if (excluded[index]) return@forEachIndexed
                                when (classify(capture, point, anchorGeneration)) {
                                    GoogleFogSpikePixelClass.MATCH -> Unit
                                    GoogleFogSpikePixelClass.PLACEHOLDER -> placeholderDuringRepaint += 1
                                    GoogleFogSpikePixelClass.STALE_PALETTE -> staleSamples += 1
                                    GoogleFogSpikePixelClass.OTHER -> {
                                        basemapLeakSamples += 1
                                        cycleLeakSamples += 1
                                    }
                                }
                            }
                            capture.bitmap.recycle()
                        } else {
                            captureFailures += 1
                        }
                    }
                    val probe = probeOnce(scenario)
                    when {
                        probe == null -> droppedSnapshots += 1
                        !probe.proven -> {
                            snapshotSamples += 1
                            notInstalledSamples += 1
                        }
                        else -> {
                            snapshotSamples += 1
                            sawProven = true
                            proofAtMs = SpikeScenarioSupport.elapsedMillisSince(resumeStart)
                            countersAtProof = readCounters(scenario)
                        }
                    }
                }

                val identityAfter = activityIdentity(scenario)
                if (identityBefore != identityAfter) {
                    // Unexpected recreation resets counters and the retained plan; scoring it
                    // would manufacture a false FAIL.
                    invalidCycles += 1
                    SpikeScenarioSupport.awaitFallbackGone(scenario)
                    anchorGeneration = awaitInstalledGeneration(scenario)
                    scenario.onActivity {
                        it.setStatusOverlaySuppressedForTesting(true)
                        it.fogTileProviderForTesting()?.resetRequestCountersForTesting()
                    }
                    continue
                }
                val currentGeneration = installedOrNull(scenario)
                if (currentGeneration != null && currentGeneration != anchorGeneration) {
                    // A sync revision or spontaneous idle began a NEW generation mid-cycle: its
                    // full re-render answers the wrong question. Await the install, re-anchor,
                    // and exclude the cycle from every aggregate.
                    regeneratedCycles += 1
                    installLatch.get().await(30, TimeUnit.SECONDS)
                    SpikeScenarioSupport.awaitFallbackGone(scenario)
                    anchorGeneration = awaitInstalledGeneration(scenario)
                    scenario.onActivity {
                        it.fogTileProviderForTesting()?.resetRequestCountersForTesting()
                    }
                    continue
                }
                if (!sawProven) {
                    proofTimeouts += 1
                    continue
                }

                // 2 s post-proof settle: any non-MATCH on-screen probe here is a presentation
                // defect after proof, the strongest form of question (c).
                val settleDeadline = SystemClock.elapsedRealtime() + POST_PROOF_SETTLE_MILLIS
                while (SystemClock.elapsedRealtime() < settleDeadline) {
                    val liveActivity = SpikeScenarioSupport.requireActivity(scenario)
                    val liveMapView = SpikeScenarioSupport.readSurface(scenario).mapView ?: break
                    val capture = SpikeScenarioSupport.captureMapView(liveActivity, liveMapView)
                    if (capture != null) {
                        grid.forEachIndexed { index, point ->
                            if (excluded[index]) return@forEachIndexed
                            if (classify(capture, point, anchorGeneration) !=
                                GoogleFogSpikePixelClass.MATCH
                            ) {
                                postProofDefects += 1
                            }
                        }
                        capture.bitmap.recycle()
                    }
                }

                val countersAtEnd = readCounters(scenario)
                val resumeCounters = requireNotNull(countersAtResume)
                val proofCounters = requireNotNull(countersAtProof)
                val endCounters = requireNotNull(countersAtEnd)
                val sdkDelta = proofCounters.total - resumeCounters.total
                sdkTileDeltas += sdkDelta
                sdkPlaceholderDeltas += proofCounters.placeholder - resumeCounters.placeholder
                totalDeltas += endCounters.total - resumeCounters.total
                if (sdkDelta > 0L) reRequestCycles += 1
                if (cycleLeakSamples > 0) leakCycles += 1
                proofTimes += proofAtMs
                validCycles += 1
            }

            val samplingSuspect = sdkTileDeltas.any { it >= activeProbes / 4 } &&
                placeholderDuringRepaint == 0
            val proofSorted = proofTimes.sorted()
            // The verdict answers the CACHE question from the provider counters (does the SDK
            // re-request tiles across stop/start?); restart-window screen transients are
            // SP9-family visual evidence and are reported separately, never conflated here.
            val verdict = if (
                validCycles >= MINIMUM_VALID_CYCLES &&
                reRequestCycles == 0 && staleSamples == 0 &&
                proofTimeouts == 0 && postProofDefects == 0 &&
                percentile(proofSorted, 95) in 0..PROOF_P95_BOUND_MILLIS
            ) {
                "SNAPSHOT_ONLY"
            } else {
                "FULL_RERENDER"
            }

            val line = "TrailVeil SP6 stopStartCache ${renderer.asEvidenceTokens()} " +
                "api=${android.os.Build.VERSION.SDK_INT} image=${android.os.Build.PRODUCT} " +
                "seed=${ScaleBenchmarkFixture.SEED} points=$POINT_COUNT cycles=$CYCLES " +
                "validCycles=$validCycles regeneratedCycles=$regeneratedCycles " +
                "invalidCycles=$invalidCycles reRequestCycles=$reRequestCycles " +
                "restartLeakCycles=$leakCycles " +
                "sdkTileReqP50=${percentile(sdkTileDeltas.sorted(), 50)} " +
                "sdkTileReqMax=${sdkTileDeltas.maxOrNull() ?: -1} " +
                "sdkPlaceholderReqMax=${sdkPlaceholderDeltas.maxOrNull() ?: -1} " +
                "totalTileReqMax=${totalDeltas.maxOrNull() ?: -1} " +
                "repaintProofP50Ms=${percentile(proofSorted, 50)} " +
                "repaintProofP95Ms=${percentile(proofSorted, 95)} " +
                "repaintProofMaxMs=${proofSorted.maxOrNull() ?: -1} " +
                "proofTimeouts=$proofTimeouts snapshotSamples=$snapshotSamples " +
                "notInstalledSamples=$notInstalledSamples droppedSnapshots=$droppedSnapshots " +
                "captureMethod=$captureMethod captureFailures=$captureFailures " +
                "activeProbes=$activeProbes stalePaletteSamples=$staleSamples " +
                "placeholderDuringRepaintSamples=$placeholderDuringRepaint " +
                "postProofDefectSamples=$postProofDefects basemapLeakSamples=$basemapLeakSamples " +
                "samplingSuspect=$samplingSuspect verdict=$verdict engineeringEvidenceOnly"
            SpikeEvidence.emit(context, "sp6-stop-start.txt", line)
            assertTrue("SP6 run inconclusive: $line", validCycles >= MINIMUM_VALID_CYCLES)
        } finally {
            scenario.close()
        }
    }

    private fun buildGrid(width: Int, height: Int): List<Point> = buildList {
        val border = 8
        for (row in 0 until GRID_SIDE) {
            for (column in 0 until GRID_SIDE) {
                add(
                    Point(
                        border + column * (width - 2 * border) / (GRID_SIDE - 1),
                        border + row * (height - 2 * border) / (GRID_SIDE - 1),
                    ),
                )
            }
        }
    }

    private fun classify(
        capture: SpikeScenarioSupport.CaptureResult,
        point: Point,
        generation: Long,
    ): GoogleFogSpikePixelClass {
        val bitmap = capture.bitmap
        val x = point.x.coerceIn(0, bitmap.width - 1)
        val y = point.y.coerceIn(0, bitmap.height - 1)
        return GoogleFogSpikePixelClassifier.classify(bitmap[x, y], generation)
    }

    private fun activityIdentity(scenario: ActivityScenario<GoogleMapsPocActivity>): Int {
        val identity = AtomicReference(0)
        scenario.onActivity { identity.set(System.identityHashCode(it)) }
        return requireNotNull(identity.get())
    }

    private fun readCounters(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): GoogleFogTileRequestCounters? {
        val counters = AtomicReference<GoogleFogTileRequestCounters?>()
        scenario.onActivity { counters.set(it.fogTileProviderForTesting()?.requestCountersForTesting()) }
        return counters.get()
    }

    private fun installedOrNull(scenario: ActivityScenario<GoogleMapsPocActivity>): Long? {
        val generation = AtomicReference<Long?>()
        scenario.onActivity { generation.set(it.installedFogGenerationForTesting()) }
        return generation.get()
    }

    private fun awaitInstalledGeneration(scenario: ActivityScenario<GoogleMapsPocActivity>): Long {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            installedOrNull(scenario)?.let { return it }
            SystemClock.sleep(100L)
        }
        error("SP6 no installed fog generation became available")
    }

    private fun probeOnce(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): GoogleFogSpikeProbeResult? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<GoogleFogSpikeProbeResult?>()
        val issued = AtomicReference(false)
        scenario.onActivity { activity ->
            issued.set(
                activity.probeInstalledFogForTesting { probe ->
                    result.set(probe)
                    latch.countDown()
                },
            )
        }
        if (issued.get() != true) return null
        return if (latch.await(10, TimeUnit.SECONDS)) result.get() else null
    }

    private fun percentile(sorted: List<Long>, percent: Int): Long {
        if (sorted.isEmpty()) return -1L
        val index = ((sorted.size * percent) / 100).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private companion object {
        const val POINT_COUNT = 10_000
        const val CYCLES = 20
        const val MINIMUM_VALID_CYCLES = 15
        const val STOP_SETTLE_MILLIS = 500L
        const val PROOF_BOUND_MILLIS = 10_000L
        const val POST_PROOF_SETTLE_MILLIS = 2_000L
        const val PROOF_P95_BOUND_MILLIS = 1_000L
        const val GRID_SIDE = 20
        const val CALIBRATION_FRAMES = 3
        const val MINIMUM_ACTIVE_PROBES = 150
        @Suppress("unused")
        val CAMERA_ANCHOR = LatLng(25.033_964, 121.564_468)
    }
}
