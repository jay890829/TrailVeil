package app.trailveil.googlepoc

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.BuildConfig
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** SDK-renderer coverage for low zoom, seams, poles and repeated horizontal world movement. */
@RunWith(AndroidJUnit4::class)
class GoogleFogViewportBoundaryTest {
    @Test
    fun everyBoundaryMoveCompletesCoveredCanonicalHandoff() {
        assumeTrue(
            "Google PoC runtime key is not configured; host builds remain compile-only",
            BuildConfig.GOOGLE_MAPS_POC_KEY_CONFIGURED,
        )
        ActivityScenario.launch(GoogleMapsPocActivity::class.java).use { scenario ->
            val mapView = awaitMapView(scenario)
            val map = awaitGoogleMap(scenario, mapView)
            awaitCoverGone(scenario)

            val requestedCameras = listOf(
                LatLng(0.0, 179.9) to 1.0f,
                LatLng(0.0, -179.9) to 1.0f,
                LatLng(84.5, 0.0) to 2.0f,
                LatLng(-84.5, 0.0) to 2.0f,
                LatLng(0.0, 0.0) to 0.0f,
            )
            requestedCameras.forEachIndexed { index, (target, zoom) ->
                moveAndAwaitHandoff(scenario, "boundary camera ${index + 1}") {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
                }
            }

            // The SDK may clamp/normalize the camera target, but these large pixel translations
            // still force its renderer to request repeated horizontal world copies where allowed.
            // Cross the 63-colour palette boundary so generation 64 must remove/recreate the
            // native TileOverlay before signature 1 can be reused.
            repeat(64) { index ->
                moveAndAwaitHandoff(scenario, "world scroll ${index + 1}") {
                    map.moveCamera(CameraUpdateFactory.scrollBy(mapView.width * 0.75f, 0.0f))
                }
            }

            val diagnostic = AtomicReference<GoogleFogInstallDiagnostic>()
            scenario.onActivity { activity ->
                diagnostic.set(activity.fogInstallDiagnosticForTesting())
            }
            assertEquals(GoogleFogInstallPhase.INSTALLED, diagnostic.get().phase)
            assertEquals(null, diagnostic.get().refreshFailure)
            assertTrue(diagnostic.get().visualRequiredTileCount > 0)
            assertEquals(
                diagnostic.get().visualRequiredTileCount,
                diagnostic.get().visualVerifiedTileCount,
            )
            assertTrue(diagnostic.get().snapshotAttempt > 0)
        }
    }

    private fun moveAndAwaitHandoff(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        name: String,
        move: () -> Unit,
    ) {
        val installed = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.callbacks = object : GoogleMapsPocCallbacks {
                override fun onCanonicalFogInstalled(generation: Long) {
                    installed.countDown()
                }
            }
            move()
        }
        assertTrue("$name did not install canonical fog", installed.await(30, TimeUnit.SECONDS))
        awaitCoverGone(scenario)
    }

    private fun awaitMapView(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
    ): MapView {
        val reference = AtomicReference<MapView>()
        repeat(POLL_COUNT) {
            scenario.onActivity { activity ->
                reference.set(findMapView(activity.window.decorView))
            }
            reference.get()?.let { return it }
            SystemClock.sleep(POLL_MILLIS)
        }
        throw AssertionError("Google PoC MapView did not attach")
    }

    private fun awaitGoogleMap(
        scenario: ActivityScenario<GoogleMapsPocActivity>,
        mapView: MapView,
    ): GoogleMap {
        val reference = AtomicReference<GoogleMap>()
        val ready = CountDownLatch(1)
        scenario.onActivity {
            mapView.getMapAsync { map ->
                reference.set(map)
                ready.countDown()
            }
        }
        assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
        return requireNotNull(reference.get())
    }

    private fun awaitCoverGone(scenario: ActivityScenario<GoogleMapsPocActivity>) {
        repeat(POLL_COUNT) {
            val visibility = AtomicReference<Int>()
            scenario.onActivity { activity ->
                val cover = activity.window.decorView.findViewWithTag<View>(FALLBACK_TAG)
                assertNotNull("canonical cover is missing", cover)
                visibility.set(requireNotNull(cover).visibility)
            }
            if (visibility.get() == View.GONE) return
            SystemClock.sleep(POLL_MILLIS)
        }
        val diagnostic = AtomicReference<GoogleFogInstallDiagnostic>()
        scenario.onActivity { activity ->
            diagnostic.set(activity.fogInstallDiagnosticForTesting())
        }
        throw AssertionError("canonical cover stayed visible: ${diagnostic.get()}")
    }

    private fun findMapView(view: View): MapView? {
        if (view is MapView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findMapView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val FALLBACK_TAG = "trailveil_google_poc_fallback"
        const val POLL_COUNT = 120
        const val POLL_MILLIS = 250L
    }
}
