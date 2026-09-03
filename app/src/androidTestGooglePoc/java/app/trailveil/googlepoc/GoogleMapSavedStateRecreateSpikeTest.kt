package app.trailveil.googlepoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 3, SP7 (recreate half): which camera fields survive
 * `MapView.onSaveInstanceState` through `recreate()`, per bundle arm — `plain` (the historical
 * top-level forwarding), `nested` (the design §6 per-key SavedStateRegistry shape), and
 * `nested_parceled` (the registry shape with a forced Parcel marshal round-trip). The am-kill
 * process-death half deliberately remains a later stage per design §12.
 *
 * Opt-in:
 * `-PtrailveilAndroidTestBuildType=googlePoc`
 * `-Pandroid.testInstrumentationRunnerArguments.trailveilGoogleSavedStateSpike=true`
 * `-Pandroid.testInstrumentationRunnerArguments.trailveilGoogleSavedStateCycles=5`
 */
@RunWith(AndroidJUnit4::class)
class GoogleMapSavedStateRecreateSpikeTest {

    @Test
    fun cameraFieldsSurviveRecreatePerBundleMode() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilGoogleSavedStateSpike")
        SpikeScenarioSupport.assumeKeyConfigured()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cycles = InstrumentationRegistry.getArguments()
            .getString("trailveilGoogleSavedStateCycles")?.toIntOrNull() ?: DEFAULT_CYCLES

        // Diagnostic seam: `trailveilGoogleSavedStatePinRenderer=false` skips the explicit
        // MapsInitializer call so pin-induced SDK behavior differences stay attributable.
        val renderer = if (
            InstrumentationRegistry.getArguments()
                .getString("trailveilGoogleSavedStatePinRenderer") == "false"
        ) {
            GoogleRendererPinResult(requested = "none", granted = "LAZY_DEFAULT")
        } else {
            GoogleRendererPin.initialize(context, requested = "latest")
        }

        val armSummaries = ARMS.map { mode -> runArm(mode, cycles, renderer) }
        val overall = when {
            armSummaries.all { it.pass } -> "ALL_ARMS_SURVIVED"
            armSummaries.first { it.mode == GoogleMapsPocActivity.SAVED_STATE_MODE_NESTED_PARCELED }.pass &&
                armSummaries.first { it.mode == GoogleMapsPocActivity.SAVED_STATE_MODE_NESTED }.pass
            -> "PLAIN_EVIDENCE_INVALID"
            armSummaries.first { it.mode == GoogleMapsPocActivity.SAVED_STATE_MODE_NESTED_PARCELED }.pass
            -> "NESTED_ONLY_SURVIVED"
            else -> "FALLBACK_REQUIRED"
        }
        SpikeEvidence.emit(
            context,
            EVIDENCE_FILE,
            "TrailVeil SP7 recreate-summary ${renderer.asEvidenceTokens()} " +
                "api=${android.os.Build.VERSION.SDK_INT} product=${android.os.Build.PRODUCT} " +
                "cycles=$cycles overall=$overall engineeringEvidenceOnly",
        )
        assertTrue(
            "SP7 saved-state fallback required: ${armSummaries.joinToString { it.line }}",
            overall != "FALLBACK_REQUIRED",
        )
    }

    private data class FieldDeltas(
        var lat: Double = 0.0,
        var lng: Double = 0.0,
        var zoom: Double = 0.0,
        var bearing: Double = 0.0,
        var tilt: Double = 0.0,
    )

    private data class ArmSummary(val mode: String, val pass: Boolean, val line: String)

    private fun runArm(mode: String, cycles: Int, renderer: GoogleRendererPinResult): ArmSummary {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GoogleMapsPocActivity::class.java)
            .putExtra(GoogleMapsPocActivity.EXTRA_SAVED_STATE_MODE, mode)
        val scenario = ActivityScenario.launch<GoogleMapsPocActivity>(intent)
        try {
            var map = SpikeScenarioSupport.awaitGoogleMap(
                scenario,
                SpikeScenarioSupport.awaitMapView(scenario),
            )
            SpikeScenarioSupport.awaitFallbackGone(scenario)

            val maxDeltas = FieldDeltas()
            val verdicts = linkedMapOf(
                "lat" to "SURVIVED", "lng" to "SURVIVED", "zoom" to "SURVIVED",
                "bearing" to "SURVIVED", "tilt" to "SURVIVED",
            )
            var clobberSkippedAlways = true
            var providerTagAlwaysMatched = true
            var parcelBytes: Int? = null
            var parcelFailure: String? = null
            var readyVsStableDiverged = false
            var maxTimeToStableMs = 0L
            var unrestoredDefaultCycles = 0

            for (cycle in 1..cycles) {
                // Seed distinctive non-default values; bearing varies per cycle so a one-cycle
                // stale restore misses its epsilon by ~15 degrees.
                val seedBearing = (32.5f + 15f * cycle) % 360f
                val seedMap = map
                val seedIdle = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.callbacks = object : GoogleMapsPocCallbacks {
                        override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                            seedIdle.countDown()
                        }
                    }
                    seedMap.moveCamera(
                        com.google.android.gms.maps.CameraUpdateFactory.newCameraPosition(
                            com.google.android.gms.maps.model.CameraPosition.Builder()
                                .target(SEED_TARGET)
                                .zoom(SEED_ZOOM)
                                .bearing(seedBearing)
                                .tilt(SEED_TILT)
                                .build(),
                        ),
                    )
                }
                // Idle only — NOT install. The recorded tilt-LOD stall means a tilted install
                // never completes; saved state is independent of it, and the recreate happens
                // well inside the 15 s install timeout.
                assertTrue("SP7 seed never idled", seedIdle.await(10, TimeUnit.SECONDS))
                val pre = awaitStableCamera(scenario, rejectDefaults = false, boundMillis = 5_000L)
                    ?: error("SP7 could not obtain a stable PRE camera in cycle $cycle")

                scenario.recreate()

                map = SpikeScenarioSupport.awaitGoogleMap(
                    scenario,
                    SpikeScenarioSupport.awaitMapView(scenario),
                )
                val readyRead = readCamera(scenario)
                val restoreStart = SystemClock.elapsedRealtimeNanos()
                // No fallback wait: the restored tilted camera stalls its own install; the camera
                // itself is readable regardless. The POST read must land before that instance's
                // 15 s install timeout, and the flat heal below cancels it via move-start.
                val post = awaitStableCamera(scenario, rejectDefaults = true, boundMillis = 8_000L)
                maxTimeToStableMs = maxOf(
                    maxTimeToStableMs,
                    SpikeScenarioSupport.elapsedMillisSince(restoreStart),
                )
                val diagnostic = AtomicReference<GoogleSavedStateDiagnostic>()
                scenario.onActivity { activity ->
                    diagnostic.set(activity.savedStateDiagnosticForTesting())
                }
                val facts = requireNotNull(diagnostic.get())

                // Flat heal: cancels the stalled tilted install's timeout via move-start and
                // completes a flat install so the next cycle starts healthy.
                val healIdle = CountDownLatch(1)
                val healMap = map
                scenario.onActivity { activity ->
                    activity.callbacks = object : GoogleMapsPocCallbacks {
                        override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                            healIdle.countDown()
                        }
                    }
                    // Explicit flat pose: newLatLngZoom would keep the restored tilt and the
                    // tilt-LOD stall would simply recur under the heal.
                    healMap.moveCamera(
                        com.google.android.gms.maps.CameraUpdateFactory.newCameraPosition(
                            com.google.android.gms.maps.model.CameraPosition.Builder()
                                .target(SEED_TARGET)
                                .zoom(SEED_ZOOM)
                                .tilt(0f)
                                .bearing(0f)
                                .build(),
                        ),
                    )
                }
                healIdle.await(10, TimeUnit.SECONDS)
                SpikeScenarioSupport.awaitFallbackGone(scenario)
                clobberSkippedAlways = clobberSkippedAlways && facts.initialCameraMoveSkipped
                if (mode != GoogleMapsPocActivity.SAVED_STATE_MODE_PLAIN) {
                    providerTagAlwaysMatched =
                        providerTagAlwaysMatched && facts.providerTagMatched == true
                    parcelBytes = facts.parcelRoundTripBytes ?: parcelBytes
                    parcelFailure = facts.parcelFailureClass ?: parcelFailure
                }

                if (post == null) {
                    if (SpikeScenarioSupport.isTerminalFallback(scenario)) {
                        error(
                            "SP7 cycle $cycle: install timeout fail-closed the instance before " +
                                "the POST read — harness timing, not saved-state evidence",
                        )
                    }
                    // The stable read never left the SDK's uninitialized default: nothing was
                    // restored at all — a distinct verdict, not DRIFTED.
                    unrestoredDefaultCycles += 1
                    verdicts.keys.forEach { field -> verdicts[field] = "UNRESTORED_DEFAULT" }
                    continue
                }
                if (readyRead != null && !camerasEqual(readyRead, post)) {
                    readyVsStableDiverged = true
                }

                val latDelta = abs(post.latitude - pre.latitude)
                val lngDelta = abs(post.longitude - pre.longitude)
                val zoomDelta = abs(post.zoom - pre.zoom).toDouble()
                val bearingDelta = angularDelta(post.bearing.toDouble(), pre.bearing.toDouble())
                val tiltDelta = abs(post.tilt - pre.tilt).toDouble()
                maxDeltas.lat = maxOf(maxDeltas.lat, latDelta)
                maxDeltas.lng = maxOf(maxDeltas.lng, lngDelta)
                maxDeltas.zoom = maxOf(maxDeltas.zoom, zoomDelta)
                maxDeltas.bearing = maxOf(maxDeltas.bearing, bearingDelta)
                maxDeltas.tilt = maxOf(maxDeltas.tilt, tiltDelta)
                judge(verdicts, "lat", latDelta, EPSILON_DEGREES)
                judge(verdicts, "lng", lngDelta, EPSILON_DEGREES)
                judge(verdicts, "zoom", zoomDelta, EPSILON_ZOOM)
                judge(verdicts, "bearing", bearingDelta, EPSILON_ANGLE)
                judge(verdicts, "tilt", tiltDelta, EPSILON_ANGLE)
            }

            val fieldsPass = verdicts.values.all { it == "SURVIVED" }
            val pass = fieldsPass && clobberSkippedAlways && unrestoredDefaultCycles == 0 &&
                (mode == GoogleMapsPocActivity.SAVED_STATE_MODE_PLAIN || providerTagAlwaysMatched)
            val line = "TrailVeil SP7 recreate savedState mode=$mode " +
                "${renderer.asEvidenceTokens()} api=${android.os.Build.VERSION.SDK_INT} " +
                "image=${android.os.Build.PRODUCT} cycles=$cycles " +
                "latMaxDeltaDeg=${fmt(maxDeltas.lat)} lngMaxDeltaDeg=${fmt(maxDeltas.lng)} " +
                "zoomMaxDelta=${fmt(maxDeltas.zoom)} bearingMaxDeltaDeg=${fmt(maxDeltas.bearing)} " +
                "tiltMaxDeltaDeg=${fmt(maxDeltas.tilt)} " +
                "lat=${verdicts["lat"]} lng=${verdicts["lng"]} zoom=${verdicts["zoom"]} " +
                "bearing=${verdicts["bearing"]} tilt=${verdicts["tilt"]} " +
                "unrestoredDefaultCycles=$unrestoredDefaultCycles " +
                "clobberSkipped=$clobberSkippedAlways " +
                "providerTagMatched=${if (mode == GoogleMapsPocActivity.SAVED_STATE_MODE_PLAIN) "na" else "$providerTagAlwaysMatched"} " +
                "parcelBytes=${parcelBytes ?: "na"} parcelFailure=${parcelFailure ?: "none"} " +
                "readyVsStableDiverged=$readyVsStableDiverged " +
                "timeToStableRestoreMsMax=$maxTimeToStableMs result=${if (pass) "PASS" else "FAIL"}"
            SpikeEvidence.emit(
                InstrumentationRegistry.getInstrumentation().targetContext,
                EVIDENCE_FILE,
                line,
            )
            return ArmSummary(mode, pass, line)
        } finally {
            scenario.close()
        }
    }

    private fun judge(
        verdicts: LinkedHashMap<String, String>,
        field: String,
        delta: Double,
        epsilon: Double,
    ) {
        if (delta > epsilon && verdicts[field] == "SURVIVED") {
            verdicts[field] = "DRIFTED(${fmt(delta)})"
        }
    }

    private fun readCamera(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): GoogleMapsPocCamera? {
        val read = AtomicReference<GoogleMapsPocCamera?>()
        scenario.onActivity { activity -> read.set(activity.cameraFieldsForTesting()) }
        return read.get()
    }

    /**
     * Two consecutive equal reads 100 ms apart, within a 10 s bound. With [rejectDefaults] the
     * stable value must additionally differ from the SDK's uninitialized default signature
     * (target ~(0,0) at floor zoom), because the SDK may apply restored state only at first
     * layout and an early equal-pair would latch the pre-restore default.
     */
    private fun awaitStableCamera(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        rejectDefaults: Boolean,
        boundMillis: Long = STABLE_READ_BOUND_MILLIS,
    ): GoogleMapsPocCamera? {
        var previous: GoogleMapsPocCamera? = null
        val deadline = SystemClock.elapsedRealtime() + boundMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = readCamera(scenario)
            if (current != null && previous != null && camerasEqual(current, previous)) {
                val isDefault = abs(current.latitude) < 0.5 && abs(current.longitude) < 0.5
                if (!rejectDefaults || !isDefault) return current
            }
            previous = current
            SystemClock.sleep(100L)
        }
        return null
    }

    private fun camerasEqual(a: GoogleMapsPocCamera, b: GoogleMapsPocCamera): Boolean =
        abs(a.latitude - b.latitude) <= EPSILON_DEGREES &&
            abs(a.longitude - b.longitude) <= EPSILON_DEGREES &&
            abs(a.zoom - b.zoom) <= EPSILON_ZOOM &&
            angularDelta(a.bearing.toDouble(), b.bearing.toDouble()) <= EPSILON_ANGLE &&
            abs(a.tilt - b.tilt) <= EPSILON_ANGLE

    private fun angularDelta(a: Double, b: Double): Double {
        val raw = abs((a - b) % 360.0)
        return minOf(raw, 360.0 - raw)
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.7f", value)

    private companion object {
        const val DEFAULT_CYCLES = 5
        const val STABLE_READ_BOUND_MILLIS = 10_000L
        const val EPSILON_DEGREES = 1e-6
        const val EPSILON_ZOOM = 0.01
        const val EPSILON_ANGLE = 0.1
        const val EVIDENCE_FILE = "sp7-recreate.txt"

        // Stage-3 finding (2026-08-27, deterministic): at tilt 30 / z17.25 the tilted far plane
        // makes the SDK request lower-zoom LOD tiles while the coverage barrier waits on
        // floor-zoom tiles it will never request — pendingTiles=7, install stalls, the 15 s
        // install timeout then fail-closes the activity. Recorded for the stage-4/5 coverage
        // design; the seed here stays tilted but at a zoom low enough that the far plane still
        // resolves at floor zoom.
        const val SEED_ZOOM = 14.25f
        const val SEED_TILT = 30f

        /** >= 0.005 deg from POC_START so a clobber overshoots the epsilon by >5000x. */
        val SEED_TARGET = com.google.android.gms.maps.model.LatLng(25.0412, 121.5710)
        val ARMS = listOf(
            GoogleMapsPocActivity.SAVED_STATE_MODE_PLAIN,
            GoogleMapsPocActivity.SAVED_STATE_MODE_NESTED,
            GoogleMapsPocActivity.SAVED_STATE_MODE_NESTED_PARCELED,
        )
    }
}
