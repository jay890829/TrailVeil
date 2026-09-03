package app.trailveil.googlepoc

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-005` stage 3, SP2: where does the Google watermark live (view hierarchy or in-surface),
 * does it stay visible above installed opaque fog (steady state and after a follow-ease-style
 * animateCamera), is it captured by `map.snapshot()`, and where does the compass sit at
 * bearing != 0 (with its auto-hide behavior tagging the exclusion rect conditional)? Identity
 * corroboration is fog-delta based (pixels visibly not fog), never an assumed logo colour.
 *
 * Feeds `GoogleAttributionVisibleTest`'s locator and the probe planner's permanent
 * watermark/compass exclusion rects.
 *
 * Opt-in: `trailveilSpikeSp2=true`; `trailveilSpikeRenderer` (default legacy).
 */
@RunWith(AndroidJUnit4::class)
class GoogleWatermarkStackingSpikeTest {

    @Test
    fun watermarkSitsAboveFogAndItsIdentityIsRecorded() {
        SpikeScenarioSupport.assumeSpikeArgument("trailveilSpikeSp2")
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
                GoogleMapSpikeSettings.applySection8Hardening(map)
            }
            val activity = SpikeScenarioSupport.requireActivity(scenario)
            val generation = installedGeneration(scenario)

            // (1) Hierarchy walk + structural dump.
            val watermarkHolder = AtomicReference<SpikeCaptureSupport.LocatorObservation>()
            val treeDump = AtomicReference<List<String>>()
            scenario.onActivity {
                watermarkHolder.set(SpikeCaptureSupport.locateWatermark(mapView))
                treeDump.set(SpikeCaptureSupport.dumpViewTree(mapView))
            }
            val watermark = requireNotNull(watermarkHolder.get())
            treeDump.get()?.forEach { line ->
                SpikeEvidence.emit(context, "sp2-viewtree.txt", line)
            }

            // (2) Screen visibility over installed fog (fog-delta corroboration).
            val steadyScreen = SpikeScenarioSupport.captureScreenTruth(mapView)
                ?: error("SP2 steady screen capture failed")
            val steadyDelta = SpikeCaptureSupport.fogDeltaCount(
                steadyScreen.bitmap,
                watermark.boundsInMapViewPx,
                generation,
            )
            // Oracle-integrity: outside the watermark/compass rects the same capture must be
            // overwhelmingly fog, or fog install silently failed and "visible" means nothing.
            val (exclusions, _) = locatorExclusions(scenario, mapView)
            val outside = SpikeCaptureSupport.countNonFog(steadyScreen.bitmap, exclusions)
            val outsideFogPct = 100.0 - outside.nonFogPx * 100.0 / outside.analyzedPx
            steadyScreen.bitmap.recycle()

            // (3) Snapshot identity.
            val snapshot = directSnapshot(scenario, map)
            val snapshotDelta = snapshot?.let { bitmap ->
                val scaleX = bitmap.width.toDouble() / mapView.width
                val scaled = Rect(
                    (watermark.boundsInMapViewPx.left * scaleX).toInt(),
                    (watermark.boundsInMapViewPx.top * scaleX).toInt(),
                    (watermark.boundsInMapViewPx.right * scaleX).toInt(),
                    (watermark.boundsInMapViewPx.bottom * scaleX).toInt(),
                )
                SpikeCaptureSupport.fogDeltaCount(bitmap, scaled, generation)
            } ?: -1
            snapshot?.recycle()

            // (4) Post-ease visibility (250 ms animateCamera, follow-ease shape).
            val idle = CountDownLatch(1)
            scenario.onActivity { poc ->
                poc.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                        idle.countDown()
                    }
                }
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(POST_EASE_TARGET, 16f),
                    250,
                    null,
                )
            }
            assertTrue("SP2 post-ease never idled", idle.await(10, TimeUnit.SECONDS))
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            // The ease starts a fog generation whose opaque canonical cover blankets the whole
            // MapView (watermark included) until the install proves — measured on API 34, where
            // a single immediate capture read delta 0. Poll until the watermark re-emerges.
            var postEaseDelta = 0
            val postEaseDeadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < postEaseDeadline) {
                val postEaseGeneration = installedGeneration(scenario)
                val postEaseScreen = SpikeScenarioSupport.captureScreenTruth(mapView)
                    ?: error("SP2 post-ease capture failed")
                postEaseDelta = SpikeCaptureSupport.fogDeltaCount(
                    postEaseScreen.bitmap,
                    watermark.boundsInMapViewPx,
                    postEaseGeneration,
                )
                postEaseScreen.bitmap.recycle()
                if (postEaseDelta >= VISIBLE_DELTA_THRESHOLD) break
                SystemClock.sleep(500L)
            }

            // (5) Compass at bearing 90; auto-hide poll back at bearing 0.
            val bearingIdle = CountDownLatch(1)
            scenario.onActivity { poc ->
                poc.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                        bearingIdle.countDown()
                    }
                }
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(POST_EASE_TARGET)
                            .zoom(16f)
                            .bearing(90f)
                            .build(),
                    ),
                )
            }
            assertTrue("SP2 bearing move never idled", bearingIdle.await(10, TimeUnit.SECONDS))
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            val compassHolder = AtomicReference<SpikeCaptureSupport.LocatorObservation>()
            scenario.onActivity { compassHolder.set(SpikeCaptureSupport.locateCompass(mapView)) }
            val compass = requireNotNull(compassHolder.get())
            val bearingGeneration = installedGeneration(scenario)
            val compassScreen = SpikeScenarioSupport.captureScreenTruth(mapView)
                ?: error("SP2 compass capture failed")
            val compassDelta = SpikeCaptureSupport.fogDeltaCount(
                compassScreen.bitmap,
                compass.boundsInMapViewPx,
                bearingGeneration,
            )
            compassScreen.bitmap.recycle()

            val backIdle = CountDownLatch(1)
            scenario.onActivity { poc ->
                poc.callbacks = object : GoogleMapsPocCallbacks {
                    override fun onCameraIdle(camera: GoogleMapsPocCamera) {
                        backIdle.countDown()
                    }
                }
                map.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(POST_EASE_TARGET).zoom(16f).bearing(0f).build(),
                    ),
                )
            }
            backIdle.await(10, TimeUnit.SECONDS)
            SpikeScenarioSupport.awaitFallbackGone(scenario)
            // The SDK fades the compass out after a multi-second delay: poll up to 10 s.
            var compassAutoHides = false
            val hideDeadline = SystemClock.elapsedRealtime() + 10_000L
            val zeroGeneration = installedGeneration(scenario)
            while (SystemClock.elapsedRealtime() < hideDeadline && !compassAutoHides) {
                val frame = SpikeScenarioSupport.captureScreenTruth(mapView) ?: break
                val delta = SpikeCaptureSupport.fogDeltaCount(
                    frame.bitmap,
                    compass.boundsInMapViewPx,
                    zeroGeneration,
                )
                frame.bitmap.recycle()
                if (delta < VISIBLE_DELTA_THRESHOLD) compassAutoHides = true
                SystemClock.sleep(500L)
            }

            // Identity + verdict per the corrected criteria (IN_SURFACE accepted).
            val watermarkIdentity = when {
                watermark.found -> "VIEW(${watermark.strategy})"
                steadyDelta >= VISIBLE_DELTA_THRESHOLD -> "IN_SURFACE"
                else -> "NOT_FOUND"
            }
            val compassIdentity = when {
                compass.found -> "VIEW(${compass.strategy})"
                compassDelta >= VISIBLE_DELTA_THRESHOLD -> "IN_SURFACE"
                else -> "NOT_FOUND"
            }
            val watermarkVisible = steadyDelta >= VISIBLE_DELTA_THRESHOLD &&
                postEaseDelta >= VISIBLE_DELTA_THRESHOLD
            // F1 (V02-005-spikes.md): the LATEST renderer draws basemap labels ABOVE the fog
            // overlay — measured 12.16% of probes on this image — so "outside is pure fog" can
            // never reach 99%. The oracle only needs to catch a FAILED install (0–50%); SP1
            // owns quantifying the label leak.
            val oracleValid = outsideFogPct >= 80.0
            val pass = oracleValid && watermarkIdentity != "NOT_FOUND" && watermarkVisible &&
                compassIdentity != "NOT_FOUND"

            val line = "TRAILVEIL-SP2 ${renderer.asEvidenceTokens()} " +
                "api=${android.os.Build.VERSION.SDK_INT} product=${android.os.Build.PRODUCT} " +
                "watermarkIdentity=$watermarkIdentity watermarkClass=${watermark.viewClass} " +
                "hierarchyPath=${watermark.hierarchyPath} " +
                "rectPx=${watermark.boundsInMapViewPx.flattenToString()} " +
                "rectNorm=${watermark.boundsNormalized} " +
                "steadyFogDeltaPx=$steadyDelta postEaseFogDeltaPx=$postEaseDelta " +
                "snapshotFogDeltaPx=$snapshotDelta " +
                "snapshotContainsWatermark=${snapshotDelta >= VISIBLE_DELTA_THRESHOLD} " +
                "outsideFogPct=${"%.2f".format(outsideFogPct)} " +
                "compassIdentity=$compassIdentity " +
                "compassRectPx=${compass.boundsInMapViewPx.flattenToString()} " +
                "compassFogDeltaPx=$compassDelta compassAutoHides=$compassAutoHides " +
                "exclusionMarginPx=${SpikeCaptureSupport.EXCLUSION_MARGIN_PX} " +
                "result=${if (pass) "PASS" else "FAIL"}"
            SpikeEvidence.emit(context, "sp2-watermark.txt", line)

            // The planner exclusion-rect deliverable in a stable KEY=value form.
            SpikeEvidence.emit(
                context,
                "probe-exclusion-rects.txt",
                "watermarkRectNorm=${watermark.boundsNormalized} " +
                    "watermarkIdentity=$watermarkIdentity " +
                    "compassRectNorm=${compass.boundsNormalized} " +
                    "compassIdentity=$compassIdentity compassConditional=$compassAutoHides " +
                    "marginPx=${SpikeCaptureSupport.EXCLUSION_MARGIN_PX} " +
                    "api=${android.os.Build.VERSION.SDK_INT} renderer=${renderer.granted}",
            )
            assertTrue("SP2 ToS-blocking FAIL (owner escalation per design §10): $line", pass)
        } finally {
            scenario.close()
        }
    }

    private fun locatorExclusions(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        mapView: com.google.android.gms.maps.MapView,
    ): Pair<List<Rect>, Boolean> {
        val holder = AtomicReference<Pair<List<Rect>, Boolean>>()
        scenario.onActivity { holder.set(SpikeCaptureSupport.liveExclusionRects(mapView)) }
        return requireNotNull(holder.get())
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

    private companion object {
        const val VISIBLE_DELTA_THRESHOLD = 50
        val POST_EASE_TARGET = com.google.android.gms.maps.model.LatLng(25.0380, 121.5600)
    }
}
