package app.trailveil.map

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource

@RunWith(AndroidJUnit4::class)
class MapSurfaceLifecycleTest {
    @Test
    fun productionFogSourceAndLayerAreInstalled() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertProductionFogInstalled(scenario)
        }
    }

    /**
     * The history detail map deliberately draws into the window so it cannot outlive its screen.
     * The main map is the screen, so it keeps MapLibre's faster compositor-layer surface — this
     * pins that asymmetry, because losing it would cost a per-frame copy on the map that pans.
     */
    @Test
    fun theMainMapKeepsItsOwnCompositorLayer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val renderView = awaitMapView(scenario).renderView
            assertTrue(
                "Main map render view was ${renderView.javaClass.name}, not a surface layer",
                renderView is SurfaceView,
            )
        }
    }

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
            assertProductionFogInstalled(scenario)
        }
    }

    private fun assertProductionFogInstalled(scenario: ActivityScenario<MainActivity>) {
        val installed = AtomicBoolean(false)
        repeat(100) {
            scenario.onActivity { activity ->
                val mapView = activity.window.decorView.findMapView()
                mapView?.getMapAsync { map ->
                    val style = map.style
                    val slot = (mapView.getTag(R.id.map_fog_active_slot) as? String)
                        ?.let(FogGenerationSlot::valueOf)
                    installed.set(
                        slot != null &&
                            style?.getSourceAs<ImageSource>(FogOverlayIds.source(slot)) != null &&
                            style.getLayerAs<RasterLayer>(FogOverlayIds.layer(slot)) != null,
                    )
                }
            }
            if (installed.get()) return
            Thread.sleep(100L)
        }
        assertTrue("Production fog source/layer were not installed", installed.get())
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
