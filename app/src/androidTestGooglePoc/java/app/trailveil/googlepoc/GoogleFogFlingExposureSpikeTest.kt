package app.trailveil.googlepoc

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 3, SP5 — the named checkmate risk: a fast fling into a never-requested fogged
 * region; worst-case duration and area of basemap-colored pixels at the leading edge. The owner
 * decides on the measured bound; this harness's job is to make the number unimpeachable:
 * never-requested is operational (tile-request log empty over the destination corridor at fling
 * start, >= 4 corridor tiles first-requested during the window), the analyzer is falsified
 * against a detached overlay, the motion window is clapper-delimited in-band, and frames at or
 * after the first post-idle clearTileCache attempt are discarded (SP9's transient, not fling
 * exposure). Emulator-only numbers: V02-007 owns device bounds.
 *
 * Opt-in: `trailveilGoogleFogFlingExposure=true`; knobs `trailveilFlingExposureMode`
 * (measure|falsify|unwarmed), `trailveilFlingExposureTrials`, `trailveilFlingExposureProfile`
 * (maxFling|tripleFling), `trailveilFlingExposureRenderer`.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogFlingExposureSpikeTest {

    @Test
    fun flingIntoNeverRequestedFogMeasuresExposure() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilGoogleFogFlingExposure")
        SpikeScenarioSupport.assumeKeyConfigured()
        SpikeScenarioSupport.assumeEmptyCanonicalTables()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val mode = arguments.getString("trailveilFlingExposureMode") ?: "measure"
        val trials = arguments.getString("trailveilFlingExposureTrials")?.toIntOrNull() ?: 5
        val profile = arguments.getString("trailveilFlingExposureProfile") ?: "maxFling"
        val requested = arguments.getString("trailveilFlingExposureRenderer") ?: "latest"
        val renderer = GoogleRendererPin.initialize(context, requested)

        if (mode == "measure") warmBasemapCorridor()

        var validTrials = 0
        var exposedTrials = 0
        var exposureMsMax = 0L
        var exposureFramesMax = 0
        var exposureAreaPctMax = 0.0
        var recoveryMsMax = 0L
        var frameGapMsMax = 0L
        var noiseFloorPctMax = 0.0
        var falsifyPctMax = 0.0
        val invalidReasons = mutableListOf<String>()

        repeat(trials) { trialIndex ->
            val outcome = runTrial(mode, profile, trialIndex)
            when (outcome) {
                is TrialOutcome.Invalid -> invalidReasons += outcome.reason
                is TrialOutcome.Falsify -> {
                    falsifyPctMax = maxOf(falsifyPctMax, outcome.exposurePct)
                    validTrials += 1
                }
                is TrialOutcome.Valid -> {
                    validTrials += 1
                    noiseFloorPctMax = maxOf(noiseFloorPctMax, outcome.noiseFloorPct)
                    frameGapMsMax = maxOf(frameGapMsMax, outcome.frameGapMs)
                    if (outcome.exposedFrames > 0) {
                        exposedTrials += 1
                        exposureMsMax = maxOf(exposureMsMax, outcome.exposureMs)
                        exposureFramesMax = maxOf(exposureFramesMax, outcome.exposedFrames)
                        exposureAreaPctMax = maxOf(exposureAreaPctMax, outcome.areaPctMax)
                        recoveryMsMax = maxOf(recoveryMsMax, outcome.recoveryMs)
                    }
                }
            }
        }

        val measurementValid = when (mode) {
            "falsify" -> falsifyPctMax >= FALSIFY_MINIMUM_PCT
            // The tight frame-gap bound is only needed to TRUST a no-exposure claim; when every
            // valid trial detected exposure, gaps merely widen the duration upper bound (already
            // padded into exposureMs). Detection outranks resolution.
            else -> validTrials >= (trials * 3 + 4) / 5 &&
                noiseFloorPctMax < NOISE_FLOOR_BOUND_PCT &&
                (exposedTrials == validTrials || frameGapMsMax in 1..FRAME_GAP_BOUND_MS)
        }
        val line = "SP5 fling-exposure ${renderer.asEvidenceTokens()} " +
            "api=${android.os.Build.VERSION.SDK_INT} image=${android.os.Build.PRODUCT} " +
            "emulatorOnly=true mode=$mode profile=$profile trials=$trials valid=$validTrials " +
            "exposedTrials=$exposedTrials exposureFramesMax=$exposureFramesMax " +
            "exposureMsMax=$exposureMsMax exposureAreaPctMax=${"%.2f".format(exposureAreaPctMax)} " +
            "recoveryMsMax=$recoveryMsMax frameGapMsMaxMotion=$frameGapMsMax " +
            "noiseFloorPctMax=${"%.3f".format(noiseFloorPctMax)} " +
            "falsifyExposurePct=${"%.1f".format(falsifyPctMax)} " +
            "invalidReasons=${invalidReasons.joinToString(";").ifEmpty { "none" }} " +
            "measurementValid=$measurementValid engineeringEvidenceOnly"
        SpikeEvidence.emit(context, "sp5-fling.txt", line)
        assertTrue("SP5 measurement invalid: $line", measurementValid)
    }

    private sealed interface TrialOutcome {
        data class Valid(
            val exposedFrames: Int,
            val exposureMs: Long,
            val areaPctMax: Double,
            val recoveryMs: Long,
            val frameGapMs: Long,
            val noiseFloorPct: Double,
        ) : TrialOutcome

        data class Falsify(val exposurePct: Double) : TrialOutcome
        data class Invalid(val reason: String) : TrialOutcome
    }

    /** Primes the Play-services basemap disk cache along the corridor (worst case: the basemap
     *  paints instantly under a late fog tile). Fog state does not survive — the trial launch
     *  attaches a brand-new overlay and adapter. */
    private fun warmBasemapCorridor() {
        val scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
        try {
            val map = SpikeScenarioSupport.awaitGoogleMap(
                scenario,
                SpikeScenarioSupport.awaitMapView(scenario),
            )
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            for (hop in 1..WARMUP_HOPS) {
                val idle = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.callbacks = object : GoogleMapsPocCallbacks {
                        override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                            idle.countDown()
                        }
                    }
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(START.latitude, START.longitude - hop * CORRIDOR_STEP_DEGREES),
                            START_ZOOM,
                        ),
                    )
                }
                idle.await(10, TimeUnit.SECONDS)
                SpikeScenarioSupport.awaitFallbackGone(scenario)
            }
        } finally {
            scenario.close()
        }
    }

    private fun runTrial(mode: String, profile: String, trialIndex: Int): TrialOutcome {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
        try {
            val mapView = SpikeScenarioSupport.awaitMapView(scenario)
            val map = SpikeScenarioSupport.awaitGoogleMap(scenario, mapView)
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            val requestLog = ConcurrentHashMap.newKeySet<Long>()
            val requestLogAt = ConcurrentHashMap<Long, Long>()
            scenario.onActivity { activity ->
                activity.setStatusOverlaySuppressedForTesting(true)
                activity.gestureCoverSuppressedForTesting = true
                activity.fogTileProviderForTesting()?.setTileRequestObserver { x, y, zoom, at ->
                    val key = packKey(x, y, zoom)
                    if (requestLog.add(key)) requestLogAt[key] = at
                }
            }

            // Geometry: screen rects for analysis, corridor tiles for the never-requested gate.
            val location = IntArray(2)
            instrumentation.runOnMainSync { mapView.getLocationOnScreen(location) }
            val mapRect = Rect(
                location[0],
                location[1],
                location[0] + mapView.width,
                location[1] + mapView.height,
            )
            val density = mapView.resources.displayMetrics.density
            val markerSize = (GoogleMapsPocActivity.SYNC_MARKER_SIZE * density).toInt()
            val markerRect = Rect(
                mapRect.right - markerSize - 24,
                mapRect.top,
                mapRect.right,
                mapRect.top + markerSize + 24,
            )
            val exclusionsHolder = AtomicReference<List<Rect>>()
            scenario.onActivity {
                exclusionsHolder.set(
                    SpikeCaptureSupport.liveExclusionRects(mapView).first.map { rect ->
                        Rect(
                            rect.left + mapRect.left,
                            rect.top + mapRect.top,
                            rect.right + mapRect.left,
                            rect.bottom + mapRect.top,
                        )
                    },
                )
            }
            val exclusions = requireNotNull(exclusionsHolder.get()) + markerRect

            if (mode == "falsify") {
                return runFalsify(scenario, map, mapView, mapRect, exclusions, markerRect, trialIndex)
            }

            // Never-requested gate over the corridor 1.5..4 viewport-widths west.
            val camera = AtomicReference<GoogleMapsPocCamera?>()
            scenario.onActivity { camera.set(it.cameraFieldsForTesting()) }
            val cameraNow = camera.get() ?: return TrialOutcome.Invalid("cameraUnreadable")
            // The SDK prefetches a ring beyond the viewport, so near corridor tiles may already
            // be requested — filter them out; the measurement only needs a populated
            // never-requested set lying in the fling path.
            val corridor = corridorTiles(cameraNow, mapView.width)
                .filterNot(requestLog::contains)
                .toSet()
            if (corridor.size < 8) {
                return TrialOutcome.Invalid("corridorAllPreRequested(${corridor.size})")
            }

            // Recording + clapper + fling + idle-synchronous end clapper.
            val recordingDone = CountDownLatch(1)
            Thread {
                shellRaw(
                    "screenrecord --bit-rate 20000000 --time-limit $RECORD_SECONDS " +
                        "/data/local/tmp/sp5_trial.mp4",
                )
                recordingDone.countDown()
            }.start()
            SystemClock.sleep(1_500L)

            val idleAt = AtomicLong(0L)
            val idle = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCameraIdle(cameraValue: GoogleMapsPocCamera) {
                        if (idleAt.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())) {
                            // End clapper fired synchronously at the idle instant, before any
                            // async canonical work can clear the tile cache.
                            activity.setSyncMarkerVisibleForTesting(true)
                            idle.countDown()
                        }
                    }
                }
            }
            pulseMarker(scenario, onMillis = 150L)
            val flingStart = SystemClock.elapsedRealtimeNanos()
            instrumentation.runOnMainSync { }
            if (profile == "tripleFling") {
                FlingGestureInjector.tripleFlingCameraWest(
                    mapRect.centerX(),
                    mapRect.centerY(),
                    mapRect.right,
                )
            } else {
                FlingGestureInjector.flingCameraWest(
                    mapRect.centerX(),
                    mapRect.centerY(),
                    mapRect.right,
                )
            }
            val idled = idle.await(10, TimeUnit.SECONDS)
            SystemClock.sleep(150L)
            scenario.onActivity { it.setSyncMarkerVisibleForTesting(false) }
            if (!idled) return TrialOutcome.Invalid("neverIdled")

            // Camera must have moved WEST toward the corridor.
            val after = AtomicReference<GoogleMapsPocCamera?>()
            scenario.onActivity { after.set(it.cameraFieldsForTesting()) }
            val moved = after.get() ?: return TrialOutcome.Invalid("cameraUnreadablePostFling")
            if (moved.longitude >= cameraNow.longitude - 0.001) {
                return TrialOutcome.Invalid("cameraDidNotMoveWest")
            }
            val newCorridorRequests = corridor.count { key ->
                requestLogAt[key]?.let { it >= flingStart } == true
            }
            if (newCorridorRequests < MINIMUM_NEW_CORRIDOR_TILES) {
                val worldTiles =
                    2.0.pow(floor(cameraNow.zoom.toDouble()).toInt().coerceIn(0, 22))
                val viewportWidthDegrees = mapView.width * 360.0 / (256.0 * worldTiles)
                val traveledVw =
                    (cameraNow.longitude - moved.longitude) / viewportWidthDegrees
                return TrialOutcome.Invalid(
                    "corridorNotEntered($newCorridorRequests,travelVw=${"%.2f".format(traveledVw)})",
                )
            }
            val clearAttemptAt = AtomicLong(0L)
            scenario.onActivity {
                clearAttemptAt.set(
                    it.fogOverlayControllerForTesting()?.lastClearAttemptAtNanosForTesting() ?: 0L,
                )
            }

            recordingDone.await(RECORD_SECONDS + 10L, TimeUnit.SECONDS)
            val localPath = pullRecording(context, trialIndex)
                ?: return TrialOutcome.Invalid("recordingPullFailed")

            val analysis = FlingExposureVideoAnalyzer.analyze(
                localPath,
                mapRect,
                exclusions,
                markerRect,
            )
            File(localPath).delete()
            if (analysis.clapperPulses < 2) {
                return TrialOutcome.Invalid(
                    "clapperPulses=${analysis.clapperPulses}" +
                        ",video=${analysis.videoWidth}x${analysis.videoHeight}" +
                        ",frames=${analysis.frames.size}",
                )
            }
            if (!analysis.ptsMonotonic) return TrialOutcome.Invalid("ptsNotMonotonic")

            val mapArea = mapRect.width() * mapRect.height()
            val preMotion = analysis.frames.filter { it.ptsMillis < analysis.motionStartMillis }
            val noiseFloorPct = preMotion.maxOfOrNull { it.exposedPx * 100.0 / mapArea } ?: 0.0
            val noiseThresholdPx = (mapArea * (noiseFloorPct + 0.05) / 100.0).toInt()
            // Post-idle clearTileCache frames are SP9's transient: the motion window already ends
            // at the idle-synchronous clapper, and the clear-attempt timestamp is recorded for
            // cross-checking that ordering held.
            val motionFrames = analysis.frames.filter {
                it.ptsMillis in analysis.motionStartMillis..analysis.motionEndMillis
            }
            val exposedFrames = motionFrames.filter {
                it.largestClusterPx >= FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX &&
                    it.exposedPx > noiseThresholdPx
            }
            var longestBurstMs = 0L
            var burstStart = -1L
            var previousExposed = false
            var previousPts = 0L
            motionFrames.forEach { frame ->
                val exposed = frame in exposedFrames
                if (exposed && !previousExposed) burstStart = frame.ptsMillis
                if (!exposed && previousExposed) {
                    longestBurstMs = maxOf(longestBurstMs, previousPts - burstStart)
                }
                previousExposed = exposed
                previousPts = frame.ptsMillis
            }
            if (previousExposed) longestBurstMs = maxOf(longestBurstMs, previousPts - burstStart)
            val firstExposed = exposedFrames.minOfOrNull { it.ptsMillis }
            val recoveryMs = if (firstExposed != null) {
                val recovered = analysis.frames.firstOrNull {
                    it.ptsMillis > firstExposed && it.exposedPx <= noiseThresholdPx
                }
                (recovered?.ptsMillis ?: analysis.motionEndMillis) - firstExposed
            } else {
                0L
            }
            if (exposedFrames.isNotEmpty()) {
                SpikeEvidence.emit(
                    context,
                    "sp5-fling.txt",
                    "SP5-trial $trialIndex exposedFrames=${exposedFrames.size} " +
                        "burstMs=$longestBurstMs " +
                        "areaPctMax=${
                            "%.2f".format(exposedFrames.maxOf { it.exposedPx * 100.0 / mapArea })
                        } clearAttemptRecorded=${clearAttemptAt.get() != 0L}",
                )
            }
            return TrialOutcome.Valid(
                exposedFrames = exposedFrames.size,
                exposureMs = longestBurstMs + analysis.frameGapMsMaxMotion.coerceAtLeast(0L),
                areaPctMax = exposedFrames.maxOfOrNull { it.exposedPx * 100.0 / mapArea } ?: 0.0,
                recoveryMs = recoveryMs,
                frameGapMs = analysis.frameGapMsMaxMotion,
                noiseFloorPct = noiseFloorPct,
            )
        } finally {
            scenario.close()
        }
    }

    /** Analyzer-integrity control: detached overlay, steady frames, same pipeline + exclusions —
     *  must report the bare label-dense basemap as heavily exposed. */
    private fun runFalsify(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: com.google.android.gms.maps.GoogleMap,
        mapView: com.google.android.gms.maps.MapView,
        mapRect: Rect,
        exclusions: List<Rect>,
        markerRect: Rect,
        trialIndex: Int,
    ): TrialOutcome {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario.onActivity {
            it.fogOverlayControllerForTesting()?.detach()
        }
        SystemClock.sleep(1_500L)
        val recordingDone = CountDownLatch(1)
        Thread {
            shellRaw(
                "screenrecord --bit-rate 20000000 --time-limit $FALSIFY_RECORD_SECONDS " +
                    "/data/local/tmp/sp5_falsify.mp4",
            )
            recordingDone.countDown()
        }.start()
        SystemClock.sleep(FALSIFY_RECORD_SECONDS * 1_000L)
        recordingDone.await(10, TimeUnit.SECONDS)
        val localPath = pullRecording(context, trialIndex, name = "sp5_falsify.mp4")
            ?: return TrialOutcome.Invalid("falsifyPullFailed")
        val analysis = FlingExposureVideoAnalyzer.analyze(localPath, mapRect, exclusions, markerRect)
        File(localPath).delete()
        val mapArea = mapRect.width() * mapRect.height()
        val exposedShare = analysis.frames.count {
            it.exposedPx * 100.0 / mapArea >= FALSIFY_MINIMUM_PCT
        }.toDouble() / analysis.frames.size.coerceAtLeast(1)
        val maxPct = analysis.frames.maxOfOrNull { it.exposedPx * 100.0 / mapArea } ?: 0.0
        return if (exposedShare >= 0.9) {
            TrialOutcome.Falsify(maxPct)
        } else {
            TrialOutcome.Invalid("falsifyExposedShare=${"%.2f".format(exposedShare)}")
        }
    }

    private fun pulseMarker(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        onMillis: Long,
    ) {
        scenario.onActivity { it.setSyncMarkerVisibleForTesting(true) }
        SystemClock.sleep(onMillis)
        scenario.onActivity { it.setSyncMarkerVisibleForTesting(false) }
        SystemClock.sleep(120L)
    }

    /** Tiles intersecting the viewport translated 1.5..4 widths west, at the current floor zoom. */
    private fun corridorTiles(camera: GoogleMapsPocCamera, viewportWidthPx: Int): Set<Long> {
        val zoom = floor(camera.zoom.toDouble()).toInt().coerceIn(0, 22)
        val worldTiles = 2.0.pow(zoom)
        val viewportWidthDegrees = viewportWidthPx * 360.0 / (256.0 * worldTiles)
        val tiles = mutableSetOf<Long>()
        val latSpanDegrees = viewportWidthDegrees * 2
        // Measured: a single max fling coasts well under 1.5 viewport-widths at z16, so the
        // corridor starts just past the SDK's prefetch ring; already-requested near tiles are
        // filtered at the gate, not here.
        var offset = 0.75
        while (offset <= 4.0) {
            val lonWest = camera.longitude - offset * viewportWidthDegrees
            val xTile = floor((lonWest + 180.0) / 360.0 * worldTiles).toInt()
            val centerY = latitudeToTileY(camera.latitude, worldTiles)
            for (dy in -2..2) {
                tiles += packKey(xTile, centerY + dy, zoom)
                tiles += packKey(xTile - 1, centerY + dy, zoom)
            }
            offset += 0.25
        }
        return tiles.also { check(latSpanDegrees > 0) }
    }

    private fun latitudeToTileY(latitude: Double, worldTiles: Double): Int {
        val radians = Math.toRadians(latitude)
        val y = (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0
        return floor(y * worldTiles).toInt()
    }

    private fun packKey(x: Int, y: Int, zoom: Int): Long =
        (zoom.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

    /** Raw-byte shell: never decode binary streams as text. */
    private fun shellRaw(command: String): ByteArray {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).use { stream ->
            stream.readBytes()
        }.also { descriptor.close() }
    }

    private fun pullRecording(
        context: android.content.Context,
        trialIndex: Int,
        name: String = "sp5_trial.mp4",
    ): String? {
        val bytes = shellRaw("cat /data/local/tmp/$name")
        shellRaw("rm -f /data/local/tmp/$name")
        if (bytes.size < 10_000) return null
        val directory = File(context.getExternalFilesDir(null), "spike-sp5")
        directory.mkdirs()
        val file = File(directory, "trial-$trialIndex.mp4")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private companion object {
        val START = LatLng(25.033_964, 121.564_468)
        const val START_ZOOM = 16f
        const val WARMUP_HOPS = 5
        const val CORRIDOR_STEP_DEGREES = 0.011
        const val RECORD_SECONDS = 30L
        const val FALSIFY_RECORD_SECONDS = 4L
        const val MINIMUM_NEW_CORRIDOR_TILES = 4
        const val FALSIFY_MINIMUM_PCT = 30.0
        // F1: basemap labels leak above the fog (~12% of viewport area on this image), so the
        // steady-state floor is label-dominated, never near zero. Exposure detection is
        // floor-relative; this bound only catches a broken analyzer (real exposure reads 30%+).
        const val NOISE_FLOOR_BOUND_PCT = 20.0
        const val FRAME_GAP_BOUND_MS = 40L
    }
}
