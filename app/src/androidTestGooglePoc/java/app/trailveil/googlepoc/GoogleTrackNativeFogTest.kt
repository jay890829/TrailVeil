package app.trailveil.googlepoc

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.BuildConfig
import app.trailveil.MainActivity
import app.trailveil.feature.recording.PermissionHistory
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.map.GoogleFogCoverageArm
import android.os.SystemClock
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Production binding, actual SDK gestures, same-run bare sensitivity control. No device data write. */
@RunWith(AndroidJUnit4::class)
class GoogleTrackNativeFogTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(600)
    private lateinit var store: PermissionHistoryStore
    private var previous: PermissionHistory? = null

    @Before fun setUp() = runBlocking {
        assumeTrue("requires the keyed Google test build", BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED)
        store = PermissionHistoryStore(InstrumentationRegistry.getInstrumentation().targetContext)
        previous = store.current()
        store.replaceForTesting(PermissionHistory(hasSeenIntroduction = true, hasRequestedLocation = true,
                    hasRetriedLocation = true, hasRequestedPreciseUpgrade = true,
                    hasRequestedNotifications = false))
        FogContinuityArm.TRACK_NATIVE.install()
    }

    @After fun restore() = runBlocking {
        previous?.let { store.replaceForTesting(it) }
        GoogleFogCoverageArm.reset()
    }

    @Test fun programmedNativeFloorHandoverStaysCoveredUntilOneReplacementIsProven() =
        trial(zoomIn = true, arm = FogContinuityArm.TRACK_NATIVE_FLOOR, programmed = true)

    @Test fun programmedNativeRingHandoverStaysCoveredUntilOneReplacementIsProven() =
        trial(zoomIn = true, arm = FogContinuityArm.TRACK_NATIVE, programmed = true)

    @Test fun nativeZoomInSurvivesWithoutAnIntegerZoomRebuild() = trial(zoomIn = true)

    @Test fun leavingNativeGroundOnZoomOutStillCoversAndExposesNoBasemap() = trial(zoomIn = false)

    @Test fun datelineFallbackInstallsOnBothSidesAndStillPassesTheExposureControl() {
        // The ordinary-longitude row also forces a full-world surround, without a dateline image.
        for (longitude in listOf(179.5, -179.5, 0.0)) {
            trial(zoomIn = false,
                start = GestureStartCamera(if (longitude == 0.0) "ordinary-fallback" else "dateline-fallback",
                    -25.5, longitude, 8f),
                nativeExpected = false)
        }
    }

    // `V03-013` A/B: the same three trials on the floor-only fixture arm. The ring bought this
    // surface no camera slack (its surround answers from the anchored image extent, and an
    // integer zoom survives through requiresRasterResolution == false), so every gate that held
    // under ring(2) must hold without it - and this is where that claim is made to fail if false.
    @Test fun floorArmNativeZoomInSurvivesWithoutAnIntegerZoomRebuild() =
        trial(zoomIn = true, arm = FogContinuityArm.TRACK_NATIVE_FLOOR)

    @Test fun floorArmLeavingNativeGroundOnZoomOutStillCoversAndExposesNoBasemap() =
        trial(zoomIn = false, arm = FogContinuityArm.TRACK_NATIVE_FLOOR)

    @Test fun floorArmDatelineFallbackInstallsOnBothSidesAndStillPassesTheExposureControl() {
        for (longitude in listOf(179.5, -179.5, 0.0)) {
            trial(zoomIn = false,
                start = GestureStartCamera(if (longitude == 0.0) "ordinary-fallback" else "dateline-fallback",
                    -25.5, longitude, 8f),
                nativeExpected = false, arm = FogContinuityArm.TRACK_NATIVE_FLOOR)
        }
    }

    private fun trial(
        zoomIn: Boolean,
        start: GestureStartCamera = GestureStartCamera("native-ocean", -25.5, -130.5, 16f),
        nativeExpected: Boolean = true,
        arm: FogContinuityArm = FogContinuityArm.TRACK_NATIVE,
        programmed: Boolean = false,
    ) {
        // Re-installed here, before the Activity launches, because a binding reads its profile once.
        arm.install()
        val kind = if (programmed) GestureKind.PROGRAMMED_ZOOM_IN
            else if (zoomIn) GestureKind.PINCH_ZOOM_IN else GestureKind.PINCH_ZOOM_OUT
        val report = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val harness = GestureExposureHarness.attach(scenario)
            try {
                assertTrue("basemap did not load: ${harness.diagnostics()}", harness.awaitUntil(60_000) {
                    harness.basemapLoadState() == GestureExposureHarness.ONLINE_STATE
                })
                assertTrue("fog never installed: ${harness.diagnostics()}", harness.awaitUntil(45_000) {
                    harness.generation() != null
                })
                assertTrue("entry camera never settled", harness.awaitQuietCamera())
                harness.runTrial(kind, start) { live ->
                    val expected = if (nativeExpected) "installer[trackNative[" else "installer[raster-fallback"
                    assertTrue("trial did not install its expected mode $expected: ${live.fogGates()}",
                        live.fogGates()?.contains(expected) == true)
                    if (programmed) {
                        val finished = CountDownLatch(1)
                        val cancelled = AtomicBoolean(false)
                        val from = live.cameraPosition().zoom
                        val started = SystemClock.elapsedRealtime()
                        InstrumentationRegistry.getInstrumentation().runOnMainSync {
                            live.map.animateCamera(CameraUpdateFactory.zoomTo(from + 1f), 500,
                                object : GoogleMap.CancelableCallback {
                                    override fun onFinish() { finished.countDown() }
                                    override fun onCancel() { cancelled.set(true); finished.countDown() }
                                })
                        }
                        assertTrue("programmed camera did not finish", finished.await(15, TimeUnit.SECONDS))
                        assertTrue("programmed camera was cancelled", !cancelled.get())
                        return@runTrial GestureDrive(
                            note = "programmed native zoom; no injected touch", downAtMillis = started,
                            upAtMillis = SystemClock.elapsedRealtime(), injectedDownCount = 0, attempts = 1,
                        )
                    }
                    val size = live.viewSize()
                    val attempt = SpikePinchGesture.drive(
                        label = "native/${kind.label}", origin = live.viewOrigin(),
                        width = size.first.toFloat(), height = size.second.toFloat(),
                        pointerYFraction = SpikePinchGesture.POINTER_Y_FRACTION,
                        spans = if (zoomIn) SpikePinchGesture.SPREAD_SPANS else SpikePinchGesture.PINCH_OUT_SPANS,
                        minimumZoomChange = if (zoomIn) checkNotNull(kind.minimumZoomIn)
                            else checkNotNull(kind.minimumZoomOut),
                        zoom = { live.cameraPosition().zoom }, diagnostics = { live.diagnostics() },
                    )
                    GestureDrive(
                        note = attempt.note, downAtMillis = attempt.downAtMillis,
                        upAtMillis = attempt.upAtMillis, injectedDownCount = attempt.injectedDownCount,
                        attempts = attempt.attempts,
                    )
                }
            } finally { harness.close() }
        }
        val bare = GestureExposureBareReference.measure("native", listOf("start" to report.before))["start"]
        val failures = GestureExposureVerdict.failuresFor(report, bare, arm,
            coverExpected = programmed || !zoomIn)
        GestureExposureVerdict.emit(report, bare)
        SpikeEvidence.emit(InstrumentationRegistry.getInstrumentation().targetContext,
            "v03-013-track-native.txt", "native arm=${arm.label} zoomIn=$zoomIn ${report.describe()}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        if (programmed) {
            assertTrue("programmed handover did not use exactly one guarded replacement: ${report.describe()}",
                report.coverRose && report.generationAfter == checkNotNull(report.generationBefore) + 1L)
        } else if (zoomIn) {
            assertTrue("in-extent native zoom rebuilt: ${report.describe()}",
                report.generationAfter == report.generationBefore && !report.coverRose)
        } else {
            assertTrue("negative control never left native coverage: ${report.describe()}", report.coverRose)
        }
    }
}
