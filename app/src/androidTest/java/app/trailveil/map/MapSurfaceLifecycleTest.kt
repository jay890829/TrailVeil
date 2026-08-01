package app.trailveil.map

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

@RunWith(AndroidJUnit4::class)
class MapSurfaceLifecycleTest {
    @Test
    fun cameraStateSurvivesActivityRecreation() {
        val expectedTarget = LatLng(25.0330, 121.5654)
        val expectedZoom = 13.0

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withMap(scenario) { map ->
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(expectedTarget, expectedZoom))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.recreate()

            val restored = AtomicReference<CameraPosition>()
            withMap(scenario) { map -> restored.set(map.cameraPosition) }

            val camera = restored.get()
            assertNotNull(camera)
            checkNotNull(camera)
            assertEquals(expectedTarget.latitude, camera.target?.latitude ?: Double.NaN, 0.0001)
            assertEquals(expectedTarget.longitude, camera.target?.longitude ?: Double.NaN, 0.0001)
            assertEquals(expectedZoom, camera.zoom, 0.01)
        }
    }

    private fun withMap(
        scenario: ActivityScenario<MainActivity>,
        action: (MapLibreMap) -> Unit,
    ) {
        val mapView = awaitMapView(scenario)
        val ready = CountDownLatch(1)
        scenario.onActivity {
            mapView.getMapAsync { map ->
                action(map)
                ready.countDown()
            }
        }
        assertTrue("MapLibre map did not become ready", ready.await(10, TimeUnit.SECONDS))
    }

    private fun awaitMapView(scenario: ActivityScenario<MainActivity>): MapView {
        val found = AtomicReference<MapView?>()
        repeat(50) {
            scenario.onActivity { activity ->
                found.set(activity.window.decorView.findMapView())
            }
            found.get()?.let { return it }
            Thread.sleep(100L)
        }
        error("MapView was not attached to MainActivity")
    }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }
}
