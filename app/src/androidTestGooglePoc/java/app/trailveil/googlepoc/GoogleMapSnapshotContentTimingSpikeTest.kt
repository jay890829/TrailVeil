package app.trailveil.googlepoc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.benchmark.ScaleBenchmarkFixture
import app.trailveil.data.db.TrailVeilDatabase
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 3, SP8: (a) content — are a sentinel marker, an above-fog polyline, and the
 * fog TileOverlay captured by `map.snapshot()` exactly as the live screen shows them (snapshot
 * fidelity + exclusion-zone necessity); (b) timing — snapshot latency at idle and mid-animation
 * under the 100k fixture, and does the 250 ms x 10 proof retry policy fit inside the 15 s
 * install timeout with headroom. The fog-overlay oracle runs ONLY in the overlays-removed
 * control round, so sentinel pixels can never corrupt the production-shaped proof.
 *
 * Opt-in: `trailveilGoogleSnapshotSpike=true`; `trailveilGoogleRenderer` (default latest).
 */
@RunWith(AndroidJUnit4::class)
class GoogleMapSnapshotContentTimingSpikeTest {

    private data class RoundResult(
        val markerOnScreen: Boolean,
        val markerInSnapshot: Boolean,
        val aboveOnScreen: Boolean,
        val aboveInSnapshot: Boolean,
        val belowHiddenOnScreen: Boolean,
        val belowHiddenInSnapshot: Boolean,
        val scaleX: Double,
        val scaleY: Double,
        val captureMethod: String,
    )

    @Test
    fun snapshotCapturesOverlaysWithinRetryBudget() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilGoogleSnapshotSpike")
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

        val scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
        try {
            val mapView = SpikeScenarioSupport.awaitMapView(scenario)
            val map = SpikeScenarioSupport.awaitGoogleMap(scenario, mapView)
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            scenario.onActivity { it.setStatusOverlaySuppressedForTesting(true) }

            // ---- CONTENT phase ----
            val rounds = mutableListOf<RoundResult>()
            var excludedRounds = 0
            var controlColorsAbsent = true
            var tileOverlayInSnapshotAllRounds = true
            var controlProbeSummary = ""
            repeat(CONTENT_ROUNDS) { round ->
                val overlays = addOverlays(scenario, map, mapView)
                SystemClock.sleep(1_000L)
                val probePoints = computeProbePoints(scenario, map, mapView, overlays)
                val activity = SpikeScenarioSupport.requireActivity(scenario)
                val screen = SpikeScenarioSupport.captureMapView(activity, mapView)
                val snapshot = directSnapshot(scenario, map)
                if (screen == null || snapshot == null) {
                    excludedRounds += 1
                    removeOverlays(scenario, overlays)
                    screen?.bitmap?.recycle()
                    snapshot?.recycle()
                    return@repeat
                }
                val screenTruth = judgeBitmap(screen.bitmap, probePoints, 1.0, 1.0)
                val scaleX = snapshot.width.toDouble() / mapView.width
                val scaleY = snapshot.height.toDouble() / mapView.height
                val snapshotTruth = judgeBitmap(snapshot, probePoints, scaleX, scaleY)
                screen.bitmap.recycle()
                if (!screenTruth.markerVisible || !screenTruth.aboveVisible) {
                    // Async icon upload: screen truth lacks the sentinel — the round is
                    // excluded from snapshot judgment, never scored against fidelity.
                    excludedRounds += 1
                    removeOverlays(scenario, overlays)
                    snapshot.recycle()
                    return@repeat
                }
                rounds += RoundResult(
                    markerOnScreen = screenTruth.markerVisible,
                    markerInSnapshot = snapshotTruth.markerVisible,
                    aboveOnScreen = screenTruth.aboveVisible,
                    aboveInSnapshot = snapshotTruth.aboveVisible,
                    belowHiddenOnScreen = !screenTruth.belowVisible,
                    belowHiddenInSnapshot = !snapshotTruth.belowVisible,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    captureMethod = screen.method,
                )
                snapshot.recycle()
                removeOverlays(scenario, overlays)
                SystemClock.sleep(500L)

                // Control: with overlays removed, no sentinel color may remain at the former
                // probe points, and the fog-overlay oracle must prove — decoupled from the
                // sentinels by construction.
                val controlActivity = SpikeScenarioSupport.requireActivity(scenario)
                val controlScreen = SpikeScenarioSupport.captureMapView(controlActivity, mapView)
                val controlSnapshot = directSnapshot(scenario, map)
                if (controlScreen != null && controlSnapshot != null) {
                    val screenControl = judgeBitmap(controlScreen.bitmap, probePoints, 1.0, 1.0)
                    val snapControl = judgeBitmap(
                        controlSnapshot,
                        probePoints,
                        controlSnapshot.width.toDouble() / mapView.width,
                        controlSnapshot.height.toDouble() / mapView.height,
                    )
                    if (screenControl.markerVisible || screenControl.aboveVisible ||
                        screenControl.belowVisible || snapControl.markerVisible ||
                        snapControl.aboveVisible || snapControl.belowVisible
                    ) {
                        controlColorsAbsent = false
                    }
                }
                controlScreen?.bitmap?.recycle()
                controlSnapshot?.recycle()
                val controlProbe = probeOnce(scenario)
                if (controlProbe?.proven != true) {
                    tileOverlayInSnapshotAllRounds = false
                    controlProbeSummary = controlProbe.toString()
                }
            }

            // ---- TIMING phase: idle ----
            val idleLatencies = mutableListOf<Long>()
            var nullIdle = 0
            repeat(TIMING_SAMPLES) {
                val latency = timedSnapshot(scenario, map)
                if (latency == null) nullIdle += 1 else idleLatencies += latency
            }

            // ---- TIMING phase: under animation ----
            val animLatencies = mutableListOf<Long>()
            var nullAnim = 0
            var excludedNotMoving = 0
            var terminalDuringTiming = false
            val idleLatch = AtomicReference<CountDownLatch?>(null)
            val moving = AtomicBoolean(false)
            scenario.onActivity { activity ->
                activity.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCameraMoveStarted(reason: Int) {
                        moving.set(true)
                    }

                    override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                        moving.set(false)
                        idleLatch.get()?.countDown()
                    }
                }
            }
            var leg = 0
            while (animLatencies.size + nullAnim < TIMING_SAMPLES && leg < TIMING_SAMPLES * 2) {
                if (SpikeScenarioSupport.isTerminalFallback(scenario)) {
                    terminalDuringTiming = true
                    break
                }
                val target = TOUR[leg % TOUR.size]
                val idle = CountDownLatch(1)
                idleLatch.set(idle)
                val latencyHolder = AtomicReference<Long?>(null)
                val requested = CountDownLatch(1)
                scenario.onActivity {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, TOUR_ZOOM), 400, null)
                    // Issued from the same main-loop pass as animateCamera: the renderer is
                    // provably mid-animation for the whole request-to-callback interval.
                    val start = SystemClock.elapsedRealtimeNanos()
                    try {
                        map.snapshot { bitmap ->
                            latencyHolder.set(
                                if (bitmap == null) {
                                    null
                                } else {
                                    bitmap.recycle()
                                    (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
                                },
                            )
                            requested.countDown()
                        }
                    } catch (_: Exception) {
                        requested.countDown()
                    }
                }
                val callbackArrived = requested.await(15, TimeUnit.SECONDS)
                if (!callbackArrived) {
                    nullAnim += 1
                } else {
                    val latency = latencyHolder.get()
                    if (latency == null) {
                        nullAnim += 1
                    } else if (moving.get() || latency < 400L) {
                        animLatencies += latency
                    } else {
                        excludedNotMoving += 1
                    }
                }
                idle.await(10, TimeUnit.SECONDS)
                SpikeScenarioSupport.awaitFallbackGone(scenario)
                leg += 1
            }

            val idleSorted = idleLatencies.sorted()
            val animSorted = animLatencies.sorted()
            val animP95 = percentile(animSorted, 95)
            val headroom = 15_000L - (animP95 + 250L) * 10L
            val validRounds = rounds.size
            val fidelity = rounds.all {
                it.markerInSnapshot == it.markerOnScreen && it.aboveInSnapshot == it.aboveOnScreen
            }
            val contentConclusive = validRounds >= MINIMUM_VALID_ROUNDS
            val timingPass = !terminalDuringTiming && nullIdle == 0 &&
                percentile(idleSorted, 95) in 0..IDLE_P95_BOUND &&
                animP95 in 0..ANIM_P95_BOUND && headroom >= 0 &&
                animSorted.size >= MINIMUM_ANIM_SAMPLES
            val verdict = when {
                terminalDuringTiming -> "INVALID"
                !contentConclusive -> "INCONCLUSIVE"
                !tileOverlayInSnapshotAllRounds || !controlColorsAbsent || !fidelity -> "ORACLE_ESCALATION"
                timingPass -> "RETRY_POLICY_STANDS"
                else -> "RAISE_CEILING"
            }

            val sample = rounds.firstOrNull()
            val line = "TrailVeil SP8 snapshotContentTiming ${renderer.asEvidenceTokens()} " +
                "api=${android.os.Build.VERSION.SDK_INT} image=${android.os.Build.PRODUCT} " +
                "seed=${ScaleBenchmarkFixture.SEED} points=$POINT_COUNT " +
                "captureMethod=${sample?.captureMethod ?: "none"} rounds=$CONTENT_ROUNDS " +
                "validRounds=$validRounds excludedRounds=$excludedRounds " +
                "tileOverlayInSnapshot=$tileOverlayInSnapshotAllRounds " +
                "controlProbe=${controlProbeSummary.ifEmpty { "proven" }} " +
                "markerInSnapshot=${sample?.markerInSnapshot} " +
                "polylineAboveFogInSnapshot=${sample?.aboveInSnapshot} " +
                "polylineBelowFogHiddenOnScreen=${sample?.belowHiddenOnScreen} " +
                "polylineBelowFogHiddenInSnapshot=${sample?.belowHiddenInSnapshot} " +
                "snapshotEqualsScreen=$fidelity controlColorsAbsent=$controlColorsAbsent " +
                "snapshotScaleX=${sample?.scaleX} snapshotScaleY=${sample?.scaleY} " +
                "snapIdleP50Ms=${percentile(idleSorted, 50)} snapIdleP95Ms=${percentile(idleSorted, 95)} " +
                "snapIdleMaxMs=${idleSorted.maxOrNull() ?: -1} nullSnapshotsIdle=$nullIdle " +
                "snapAnimP50Ms=${percentile(animSorted, 50)} snapAnimP95Ms=$animP95 " +
                "snapAnimMaxMs=${animSorted.maxOrNull() ?: -1} nullSnapshotsAnim=$nullAnim " +
                "excludedNotMovingSamples=$excludedNotMoving " +
                "retryBudgetHeadroomMs=$headroom verdict=$verdict engineeringEvidenceOnly"
            SpikeEvidence.emit(context, "sp8-snapshot.txt", line)
            assertTrue("SP8 $verdict: $line", verdict == "RETRY_POLICY_STANDS" || verdict == "RAISE_CEILING")
        } finally {
            scenario.close()
        }
    }

    private data class Overlays(val marker: Marker?, val above: Polyline?, val below: Polyline?)

    private data class ProbePoints(
        val marker: Point,
        val above: List<Point>,
        val below: List<Point>,
    )

    private data class Judgement(
        val markerVisible: Boolean,
        val aboveVisible: Boolean,
        val belowVisible: Boolean,
    )

    private fun addOverlays(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: GoogleMap,
        mapView: android.view.View,
    ): Overlays {
        val holder = AtomicReference<Overlays?>()
        scenario.onActivity {
            val projection = map.projection
            val center = map.cameraPosition.target
            val aboveStart = projection.fromScreenLocation(
                Point(mapView.width / 4, mapView.height * 5 / 8),
            )
            val aboveEnd = projection.fromScreenLocation(
                Point(mapView.width * 3 / 4, mapView.height * 5 / 8),
            )
            val belowStart = projection.fromScreenLocation(
                Point(mapView.width / 4, mapView.height * 3 / 8),
            )
            val belowEnd = projection.fromScreenLocation(
                Point(mapView.width * 3 / 4, mapView.height * 3 / 8),
            )
            val icon = createBitmap(48, 48).apply {
                eraseColor(MARKER_COLOR)
            }
            holder.set(
                Overlays(
                    marker = map.addMarker(
                        MarkerOptions()
                            .position(center)
                            .icon(BitmapDescriptorFactory.fromBitmap(icon))
                            .anchor(0.5f, 0.5f),
                    ),
                    above = map.addPolyline(
                        PolylineOptions().add(aboveStart, aboveEnd)
                            .color(ABOVE_COLOR).width(24f).zIndex(Float.MAX_VALUE),
                    ),
                    below = map.addPolyline(
                        PolylineOptions().add(belowStart, belowEnd)
                            .color(BELOW_COLOR).width(24f).zIndex(0f),
                    ),
                ),
            )
        }
        return requireNotNull(holder.get())
    }

    private fun removeOverlays(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        overlays: Overlays,
    ) {
        scenario.onActivity {
            overlays.marker?.remove()
            overlays.above?.remove()
            overlays.below?.remove()
        }
    }

    private fun computeProbePoints(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: GoogleMap,
        mapView: android.view.View,
        overlays: Overlays,
    ): ProbePoints {
        val holder = AtomicReference<ProbePoints?>()
        scenario.onActivity {
            val projection = map.projection
            val markerPoint = projection.toScreenLocation(
                requireNotNull(overlays.marker).position,
            )
            fun linePoints(polyline: Polyline?): List<Point> {
                val points = requireNotNull(polyline).points
                val start = projection.toScreenLocation(points.first())
                val end = projection.toScreenLocation(points.last())
                return (0 until 5).map { index ->
                    Point(
                        start.x + (end.x - start.x) * index / 4,
                        start.y + (end.y - start.y) * index / 4,
                    )
                }.filter { point ->
                    point.x in EDGE_MARGIN until mapView.width - EDGE_MARGIN &&
                        point.y in EDGE_MARGIN until mapView.height - EDGE_MARGIN
                }
            }
            holder.set(
                ProbePoints(
                    marker = markerPoint,
                    above = linePoints(overlays.above),
                    below = linePoints(overlays.below),
                ),
            )
        }
        return requireNotNull(holder.get())
    }

    private fun judgeBitmap(
        bitmap: Bitmap,
        probes: ProbePoints,
        scaleX: Double,
        scaleY: Double,
    ): Judgement {
        fun sentinelAt(point: Point, sentinel: Int, radius: Int): Boolean {
            val x = (point.x * scaleX).toInt()
            val y = (point.y * scaleY).toInt()
            var hits = 0
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val px = (x + dx).coerceIn(0, bitmap.width - 1)
                    val py = (y + dy).coerceIn(0, bitmap.height - 1)
                    if (isSentinel(bitmap[px, py], sentinel)) hits += 1
                }
            }
            return hits >= (radius * 2 + 1)
        }
        return Judgement(
            markerVisible = sentinelAt(probes.marker, MARKER_COLOR, 2),
            aboveVisible = probes.above.count { sentinelAt(it, ABOVE_COLOR, 1) } >= 4,
            belowVisible = probes.below.count { sentinelAt(it, BELOW_COLOR, 1) } >= 1,
        )
    }

    private fun isSentinel(pixel: Int, sentinel: Int): Boolean =
        abs(Color.red(pixel) - Color.red(sentinel)) <= SENTINEL_TOLERANCE &&
            abs(Color.green(pixel) - Color.green(sentinel)) <= SENTINEL_TOLERANCE &&
            abs(Color.blue(pixel) - Color.blue(sentinel)) <= SENTINEL_TOLERANCE

    private fun directSnapshot(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: GoogleMap,
    ): Bitmap? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Bitmap?>()
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
        latch.await(5, TimeUnit.SECONDS)
        return holder.get()
    }

    private fun timedSnapshot(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: GoogleMap,
    ): Long? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Long?>()
        scenario.onActivity {
            val start = SystemClock.elapsedRealtimeNanos()
            try {
                map.snapshot { bitmap ->
                    holder.set(
                        if (bitmap == null) {
                            null
                        } else {
                            bitmap.recycle()
                            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
                        },
                    )
                    latch.countDown()
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        return if (latch.await(15, TimeUnit.SECONDS)) holder.get() else null
    }

    private fun probeOnce(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): GoogleFogSpikeProbeResult? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<GoogleFogSpikeProbeResult?>()
        scenario.onActivity { activity ->
            if (!activity.probeInstalledFogForTesting { probe ->
                    result.set(probe)
                    latch.countDown()
                }
            ) {
                latch.countDown()
            }
        }
        return if (latch.await(10, TimeUnit.SECONDS)) result.get() else null
    }

    private fun percentile(sorted: List<Long>, percent: Int): Long {
        if (sorted.isEmpty()) return -1L
        val index = ((sorted.size * percent) / 100).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private companion object {
        const val POINT_COUNT = 100_000
        const val CONTENT_ROUNDS = 5
        const val MINIMUM_VALID_ROUNDS = 3
        const val TIMING_SAMPLES = 50
        const val MINIMUM_ANIM_SAMPLES = 30
        const val IDLE_P95_BOUND = 500L
        const val ANIM_P95_BOUND = 1_250L
        const val EDGE_MARGIN = 100
        const val SENTINEL_TOLERANCE = 24
        val MARKER_COLOR = Color.rgb(255, 0, 255)
        val ABOVE_COLOR = Color.rgb(0, 255, 255)
        val BELOW_COLOR = Color.rgb(255, 255, 0)
        const val TOUR_ZOOM = 14f
        val TOUR = listOf(
            LatLng(25.0280, 121.5000), LatLng(25.0420, 121.5350), LatLng(25.0660, 121.5400),
            LatLng(25.0380, 121.5100), LatLng(25.0310, 121.5280), LatLng(25.0550, 121.5650),
            LatLng(25.0180, 121.5450), LatLng(25.0750, 121.5150), LatLng(25.0450, 121.5750),
            LatLng(25.0600, 121.5700),
        )
    }
}
