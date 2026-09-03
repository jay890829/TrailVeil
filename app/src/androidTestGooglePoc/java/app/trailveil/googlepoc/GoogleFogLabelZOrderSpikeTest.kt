package app.trailveil.googlepoc

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 3, SP1: with fully-opaque fog coverage installed, do any basemap
 * label/POI/road/building/indoor pixels ever composite ABOVE the fog TileOverlay — across
 * pan/zoom/tilt/indoor poses, pre- vs post- the §8 hardening set, on both capture channels
 * (composited screen + map.snapshot)?
 *
 * Tilt poses deliberately do NOT await a canonical install: the recorded tilt-LOD finding means
 * a tilted far plane requests lower-zoom tiles the coverage barrier never sees, so the install
 * stalls; opacity there is anchored by the SDK's cached tiles plus the adapter's fail-closed
 * placeholders (any zoom), the per-move cover is suppressed (§4.2's steady-state design), and
 * the pose chain stays inside the 15 s install timeout because every next move-start cancels it.
 *
 * Opt-in: `trailveilSpikeSp1=true`; `trailveilSpikeRenderer` (default legacy — the granted
 * renderer is the pin datum).
 */
@RunWith(AndroidJUnit4::class)
class GoogleFogLabelZOrderSpikeTest {

    private data class Pose(
        val name: String,
        val target: LatLng,
        val zoom: Float,
        val tilt: Float,
        val bearing: Float,
        val awaitInstall: Boolean,
    )

    @Test
    fun labelsNeverCompositeAboveFogAcrossSweep() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilSpikeSp1")
        SpikeScenarioSupport.assumeKeyConfigured()
        SpikeScenarioSupport.assumeEmptyCanonicalTables()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requested = InstrumentationRegistry.getArguments()
            .getString("trailveilSpikeRenderer") ?: "legacy"
        val renderer = GoogleRendererPin.initialize(context, requested)

        val scenario = ActivityScenario.launch(GoogleMapsPocActivity::class.java)
        try {
            val mapView = SpikeScenarioSupport.awaitMapView(scenario)
            val map = SpikeScenarioSupport.awaitGoogleMap(scenario, mapView)
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            scenario.onActivity {
                it.setStatusOverlaySuppressedForTesting(true)
                it.gestureCoverSuppressedForTesting = true
            }

            var preLeakTotal = 0L
            var postLeakTotal = 0L
            var preNonFogSeen = false
            var invalidPoses = 0
            var maxCalibration = 0
            var discardedCaptures = 0
            var maxExcludedPct = 0.0
            var exclusionFallbackUsed = false
            var indoorFocusedObserved = false
            var indoorPickerObserved = false
            val perCaptureLines = mutableListOf<String>()

            for (phase in listOf("PRE", "POST")) {
                scenario.onActivity {
                    if (phase == "PRE") {
                        GoogleMapSpikeSettings.applyPreHardeningSweep(
                            map,
                            buildingsEnabled = true,
                            indoorEnabled = true,
                        )
                    } else {
                        GoogleMapSpikeSettings.applySection8Hardening(map)
                    }
                }
                for (pose in POSES) {
                    var poseValid = false
                    var attempts = 0
                    while (!poseValid && attempts < 3) {
                        attempts += 1
                        val idle = CountDownLatch(1)
                        scenario.onActivity { activity ->
                            activity.callbacks = object : GoogleMapsPocCallbacks {
                                override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                                    idle.countDown()
                                }
                            }
                            map.moveCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(pose.target)
                                        .zoom(pose.zoom)
                                        .tilt(pose.tilt)
                                        .bearing(pose.bearing)
                                        .build(),
                                ),
                            )
                        }
                        if (!idle.await(10, TimeUnit.SECONDS)) continue
                        if (pose.awaitInstall) {
                            SpikeScenarioSupport.awaitFallbackGone(scenario)
                        }
                        if (!awaitMapLoaded(scenario, map)) continue

                        // Applied camera (the SDK clamps tilt per zoom): evidence values come
                        // from readback, never from the pose constants.
                        val applied = AtomicReference<GoogleMapsPocCamera?>()
                        scenario.onActivity { applied.set(it.cameraFieldsForTesting()) }
                        val camera = applied.get() ?: continue

                        var capturesClean = true
                        var captureIndex = 0
                        for (delayMillis in CAPTURE_DELAYS_MILLIS) {
                            SystemClock.sleep(delayMillis)
                            captureIndex += 1
                            if (SpikeScenarioSupport.isTerminalFallback(scenario)) {
                                error(
                                    "SP1 terminal fallback during $phase/${pose.name}: " +
                                        diagnosticOf(scenario),
                                )
                            }
                            val activity = SpikeScenarioSupport.requireActivity(scenario)
                            val exclusionsHolder =
                                AtomicReference<Pair<List<android.graphics.Rect>, Boolean>>()
                            scenario.onActivity {
                                exclusionsHolder.set(SpikeCaptureSupport.liveExclusionRects(mapView))
                            }
                            val (exclusions, fallbackUsed) = requireNotNull(exclusionsHolder.get())
                            exclusionFallbackUsed = exclusionFallbackUsed || fallbackUsed

                            val initialCapture =
                                SpikeScenarioSupport.captureMapView(activity, mapView)
                            if (initialCapture == null) {
                                capturesClean = false
                                break
                            }
                            var screenCapture: SpikeScenarioSupport.CaptureResult = initialCapture
                            val generation = installedGeneration(scenario)
                            var calibration =
                                SpikeCaptureSupport.calibrationDelta(screenCapture.bitmap, generation)
                            // A capture can land inside a refresh transient (SP9: full-basemap
                            // frames, ~200 off the fog anchor). Retry the capture; a still-bad
                            // one is DISCARDED, not sweep-invalidating.
                            var calibrationRetries = 0
                            var captureLost = false
                            while (calibration > CALIBRATION_BOUND && calibrationRetries < 3) {
                                screenCapture.bitmap.recycle()
                                SystemClock.sleep(400L)
                                calibrationRetries += 1
                                val retaken = SpikeScenarioSupport.captureMapView(activity, mapView)
                                if (retaken == null) {
                                    captureLost = true
                                    break
                                }
                                screenCapture = retaken
                                calibration =
                                    SpikeCaptureSupport.calibrationDelta(retaken.bitmap, generation)
                            }
                            if (captureLost) {
                                capturesClean = false
                                break
                            }
                            val screen = screenCapture
                            if (calibration > CALIBRATION_BOUND) {
                                discardedCaptures += 1
                                perCaptureLines += "TRAILVEIL-SP1 phase=$phase pose=${pose.name} " +
                                    "capture=$captureIndex channel=discarded " +
                                    "calibrationDelta=$calibration verdict=DISCARDED"
                                screen.bitmap.recycle()
                                continue
                            }
                            maxCalibration = maxOf(maxCalibration, calibration)
                            val screenTally =
                                SpikeCaptureSupport.countNonFog(screen.bitmap, exclusions)
                            val snapshot = directSnapshot(scenario, map)
                            val snapshotTally = snapshot?.let { bitmap ->
                                val scaleX = bitmap.width.toDouble() / mapView.width
                                val scaled = exclusions.map { rect ->
                                    android.graphics.Rect(
                                        (rect.left * scaleX).toInt(),
                                        (rect.top * scaleX).toInt(),
                                        (rect.right * scaleX).toInt(),
                                        (rect.bottom * scaleX).toInt(),
                                    )
                                }
                                SpikeCaptureSupport.countNonFog(bitmap, scaled)
                            }
                            for ((channel, tally) in listOf(
                                "screen" to screenTally,
                                "mapSnapshot" to snapshotTally,
                            )) {
                                if (tally == null) continue
                                val excludedPct =
                                    tally.excludedPx * 100.0 / (tally.analyzedPx + tally.excludedPx)
                                maxExcludedPct = maxOf(maxExcludedPct, excludedPct)
                                if (phase == "PRE") {
                                    preLeakTotal += tally.nonFogPx
                                    if (tally.nonFogPx > 0) preNonFogSeen = true
                                } else {
                                    postLeakTotal += tally.nonFogPx
                                }
                                perCaptureLines += "TRAILVEIL-SP1 phase=$phase pose=${pose.name} " +
                                    "capture=$captureIndex channel=$channel " +
                                    "zoomApplied=${camera.zoom} tiltApplied=${camera.tilt} " +
                                    "bearingApplied=${camera.bearing} " +
                                    "analyzedPx=${tally.analyzedPx} excludedPx=${tally.excludedPx} " +
                                    "nonFogPx=${tally.nonFogPx} calibrationDelta=$calibration " +
                                    "verdict=${if (tally.nonFogPx == 0L.toInt()) "CLEAN" else "LEAK"}"
                                if (phase == "POST" && tally.nonFogPx > 0) {
                                    SpikeEvidence.savePng(
                                        context,
                                        screen.bitmap,
                                        "sp1-leak-$phase-${pose.name}-$captureIndex-$channel.png",
                                    )
                                }
                            }
                            snapshot?.recycle()
                            screen.bitmap.recycle()
                        }
                        if (pose.name.startsWith("I")) {
                            val focused = AtomicReference<Boolean?>()
                            scenario.onActivity {
                                focused.set(
                                    try {
                                        map.focusedBuilding != null
                                    } catch (_: Exception) {
                                        null
                                    },
                                )
                            }
                            indoorFocusedObserved = indoorFocusedObserved || focused.get() == true
                            val picker = AtomicReference(false)
                            scenario.onActivity {
                                picker.set(hasLevelPicker(mapView))
                            }
                            indoorPickerObserved = indoorPickerObserved || picker.get()
                        }
                        poseValid = capturesClean
                    }
                    if (!poseValid) invalidPoses += 1
                }
                // Return to a flat installed state between phases so the pending tilted
                // generation resolves and the activity stays healthy.
                val idle = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.callbacks = object : GoogleMapsPocCallbacks {
                        override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                            idle.countDown()
                        }
                    }
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(POSES.first().target, 16f))
                }
                idle.await(10, TimeUnit.SECONDS)
                SpikeScenarioSupport.awaitFallbackGone(scenario)
            }

            perCaptureLines.forEach { line -> SpikeEvidence.emit(context, EVIDENCE_FILE, line) }
            val oracleSensitive = preNonFogSeen
            val result = when {
                invalidPoses > 0 || maxCalibration > CALIBRATION_BOUND ||
                    maxExcludedPct > EXCLUDED_PCT_BOUND || !oracleSensitive ||
                    discardedCaptures > 24 -> "INVALID"
                postLeakTotal == 0L -> "PASS"
                else -> "FAIL"
            }
            val summary = "TRAILVEIL-SP1-SUMMARY ${renderer.asEvidenceTokens()} " +
                "api=${android.os.Build.VERSION.SDK_INT} product=${android.os.Build.PRODUCT} " +
                "posesPerPhase=${POSES.size} capturesPerPose=${CAPTURE_DELAYS_MILLIS.size * 2} " +
                "preLeakPxTotal=$preLeakTotal postLeakPxTotal=$postLeakTotal " +
                "oracleSensitive=$oracleSensitive maxCalibrationDelta=$maxCalibration " +
                "discardedCaptures=$discardedCaptures " +
                "maxExcludedPct=${"%.2f".format(maxExcludedPct)} " +
                "exclusionFallbackUsed=$exclusionFallbackUsed " +
                "indoorFocusedObserved=$indoorFocusedObserved " +
                "indoorPickerObserved=$indoorPickerObserved " +
                "invalidPoses=$invalidPoses result=$result"
            SpikeEvidence.emit(context, EVIDENCE_FILE, summary)
            assertTrue("SP1 $result: $summary", result == "PASS" || result == "FAIL")
        } finally {
            scenario.close()
        }
    }

    private fun awaitMapLoaded(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: GoogleMap,
    ): Boolean {
        val loaded = CountDownLatch(1)
        scenario.onActivity {
            // Single-slot listener: the activity's own copy already latched mapLoaded=true at
            // first load, so re-registering here is safe and gives a per-pose loaded gate.
            map.setOnMapLoadedCallback { loaded.countDown() }
        }
        return loaded.await(30, TimeUnit.SECONDS)
    }

    private fun installedGeneration(scenario: ActivityScenario<GoogleMapsPocActivity>): Long {
        val generation = AtomicReference(1L)
        scenario.onActivity {
            it.installedFogGenerationForTesting()?.let(generation::set)
        }
        return generation.get()
    }

    private fun directSnapshot(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        map: GoogleMap,
    ): android.graphics.Bitmap? {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<android.graphics.Bitmap?>()
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

    private fun hasLevelPicker(mapView: com.google.android.gms.maps.MapView): Boolean {
        // The indoor level picker is an SDK-injected vertical control on the right edge; any
        // visible non-watermark, non-compass ViewGroup child there is recorded.
        fun scan(view: android.view.View): Boolean {
            if (view is android.view.ViewGroup) {
                repeat(view.childCount) { index ->
                    if (scan(view.getChildAt(index))) return true
                }
            }
            val location = IntArray(2)
            val mapLocation = IntArray(2)
            view.getLocationOnScreen(location)
            mapView.getLocationOnScreen(mapLocation)
            val relativeX = location[0] - mapLocation[0]
            return view.isShown && view.height > view.width &&
                relativeX > mapView.width * 3 / 4 && view.height > mapView.height / 6
        }
        return scan(mapView)
    }

    private fun diagnosticOf(scenario: ActivityScenario<GoogleMapsPocActivity>): String {
        val diagnostic = AtomicReference<GoogleFogInstallDiagnostic>()
        scenario.onActivity { diagnostic.set(it.fogInstallDiagnosticForTesting()) }
        return diagnostic.get().toString()
    }

    private companion object {
        const val EVIDENCE_FILE = "sp1-zorder.txt"
        // The bound exists to catch a NON-FOG background (basemap patches read ~200 off the
        // generation color); it must tolerate placeholder-anchored poses — tilt poses skip the
        // install await (F0), and mid-delivery flats can median onto the fail-closed placeholder
        // (~50 off, measured P2/T3). Label counting itself is generation-independent.
        const val CALIBRATION_BOUND = 60
        const val EXCLUDED_PCT_BOUND = 5.0
        val CAPTURE_DELAYS_MILLIS = listOf(500L, 1_500L)
        val POSES = listOf(
            Pose("P1", LatLng(25.0340, 121.5645), 16f, 0f, 0f, awaitInstall = true),
            Pose("P2", LatLng(25.0478, 121.5170), 12f, 0f, 0f, awaitInstall = true),
            Pose("P3", LatLng(25.0330, 121.5654), 18f, 0f, 0f, awaitInstall = true),
            Pose("P4", LatLng(25.0210, 121.5350), 14f, 0f, 0f, awaitInstall = true),
            Pose("P5", LatLng(25.0910, 121.5598), 15f, 0f, 0f, awaitInstall = true),
            Pose("P6", LatLng(25.0330, 121.5645), 13f, 0f, 0f, awaitInstall = true),
            Pose("T1", LatLng(25.0336, 121.5646), 17.5f, 30f, 45f, awaitInstall = false),
            Pose("T2", LatLng(25.0336, 121.5646), 17.5f, 45f, 45f, awaitInstall = false),
            Pose("T3", LatLng(25.0336, 121.5646), 17.5f, 60f, 45f, awaitInstall = false),
            Pose("T4", LatLng(25.0336, 121.5646), 17.5f, 67.5f, 45f, awaitInstall = false),
            Pose("I1", LatLng(25.0336, 121.5646), 19f, 0f, 0f, awaitInstall = true),
            Pose("I2", LatLng(25.0336, 121.5646), 19f, 45f, 0f, awaitInstall = false),
        )
    }
}
