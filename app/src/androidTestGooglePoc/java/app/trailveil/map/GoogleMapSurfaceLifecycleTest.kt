package app.trailveil.map

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.R
import app.trailveil.googlepoc.FlingGestureInjector
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleMapSurfaceLifecycleTest {
    @Before fun setUp() = GoogleMapSurfaceTestHooks.reset()
    @After fun tearDown() = GoogleMapSurfaceTestHooks.reset()

    @Test
    fun providerTaggedCameraStateRestoresAndUiHardeningSurvives() {
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        val firstReady = armMapReady()
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            var map = firstReady.await()
            val expected = CameraPosition.Builder()
                .target(LatLng(25.033, 121.5654))
                .zoom(13.25f)
                .bearing(27f)
                .tilt(30f)
                .build()
            scenario.onActivity {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(expected))
                assertFalse(map.uiSettings.isMapToolbarEnabled)
                assertFalse(map.uiSettings.isMyLocationButtonEnabled)
                assertFalse(map.uiSettings.isZoomControlsEnabled)
                assertTrue(map.uiSettings.isCompassEnabled)
                assertFalse(map.isIndoorEnabled)
                assertFalse(map.isBuildingsEnabled)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            val restoredReady = armMapReady()
            scenario.recreate()
            map = restoredReady.await()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val restored = AtomicReference<CameraPosition>()
            scenario.onActivity { restored.set(map.cameraPosition) }
            val actual = requireNotNull(restored.get())
            assertEquals(expected.target.latitude, actual.target.latitude, 0.0001)
            assertEquals(expected.target.longitude, actual.target.longitude, 0.0001)
            assertEquals(expected.zoom, actual.zoom, 0.01f)
            assertEquals(expected.bearing, actual.bearing, 0.01f)
            assertEquals(expected.tilt, actual.tilt, 0.01f)
        }
    }

    @Test
    fun injectedFlingReachesTheBareHostedMapView() {
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        val mapReady = armMapReady()
        val viewRef = AtomicReference<com.google.android.gms.maps.MapView>()
        val viewReady = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { view ->
            viewRef.set(view)
            viewReady.countDown()
        }
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            val map = mapReady.await()
            assertTrue(viewReady.await(30, TimeUnit.SECONDS))
            val view = requireNotNull(viewRef.get())
            scenario.onActivity {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(25.033, 121.5654), 16f))
            }
            val before = AtomicReference<Double>()
            scenario.onActivity { before.set(map.cameraPosition.target.longitude) }
            val location = IntArray(2)
            scenario.onActivity { view.getLocationOnScreen(location) }
            FlingGestureInjector.flingCameraWest(
                centerX = location[0] + view.width / 2,
                centerY = location[1] + view.height / 2,
                screenWidth = view.resources.displayMetrics.widthPixels,
            )
            Thread.sleep(2_000L)
            val after = AtomicReference<Double>()
            scenario.onActivity { after.set(map.cameraPosition.target.longitude) }
            assertTrue(
                "bare host received no DOWN; view=${view.width}x${view.height} " +
                    "origin=${location.contentToString()}",
                ((view.getTag(R.id.map_touch_down_count) as? Int) ?: 0) > 0,
            )
            assertTrue(
                "bare host camera did not move",
                kotlin.math.abs(requireNotNull(after.get()) - requireNotNull(before.get())) > 0.0001,
            )
        }
    }

    private fun armMapReady(): AwaitedMap {
        val map = AtomicReference<GoogleMap>()
        val ready = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.onMapReady.set { value ->
            map.set(value)
            ready.countDown()
        }
        return AwaitedMap(map, ready)
    }

    private data class AwaitedMap(
        val value: AtomicReference<GoogleMap>,
        val ready: CountDownLatch,
    ) {
        fun await(): GoogleMap {
            assertTrue("map did not become ready", ready.await(30, TimeUnit.SECONDS))
            return requireNotNull(value.get())
        }
    }
}
