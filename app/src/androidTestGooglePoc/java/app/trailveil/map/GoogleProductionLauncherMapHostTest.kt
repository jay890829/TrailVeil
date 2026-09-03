package app.trailveil.map

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Choreographer
import androidx.core.graphics.get
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.googlepoc.FlingGestureInjector
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleProductionLauncherMapHostTest {
    @Test
    fun realMainActivityHostsTheHardenedGoogleMapUnderTheFogGuard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val mapView = awaitMapView(scenario)
            assertEquals(GestureOwningGoogleMapView::class.java, mapView.javaClass)
            assertNotNull(mapView.getTag(R.id.map_basemap_load_state))
            scenario.onActivity { activity ->
                assertFalse(
                    "a non-Google map view leaked into the googlePoc launcher",
                    activity.window.decorView.containsClassName("maplibre"),
                )
            }

            val ready = CountDownLatch(1)
            val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
            scenario.onActivity {
                mapView.getMapAsync { map ->
                    mapRef.set(map)
                    ready.countDown()
                }
            }
            assertTrue("launcher map did not become ready", ready.await(30, TimeUnit.SECONDS))
            scenario.onActivity {
                val map = requireNotNull(mapRef.get())
                assertTrue(map.uiSettings.isCompassEnabled)
                assertTrue(!map.uiSettings.isMapToolbarEnabled)
                assertTrue(!map.isIndoorEnabled)
                assertTrue(!map.isBuildingsEnabled)
            }

            val firstGeneration = awaitGeneration(mapView)
            assertEquals(false, mapView.getTag(R.id.map_fog_cover_up))
            scenario.onActivity {
                requireNotNull(mapRef.get()).animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(-33.8688, 151.2093), 12f),
                    1_200,
                    null,
                )
            }
            assertTrue(
                "programmed viewport exit never raised the safety cover",
                awaitTag(mapView, R.id.map_fog_cover_up) { value -> value == true },
            )
            assertTrue(
                "new viewport never installed a different proven generation",
                awaitTag(mapView, R.id.map_fog_canonical_generation) { value ->
                    value != null && value != firstGeneration
                },
            )
            assertTrue(
                "safety cover remained stuck after the new generation was proven",
                awaitTag(mapView, R.id.map_fog_cover_up) { value -> value == false },
            )
            val interval = mapView.getTag(R.id.map_fog_last_cover_interval_ms) as? Long
            assertNotNull("cover interval was not measured", interval)
            assertTrue("cover interval must be positive", requireNotNull(interval) > 0L)
        }
    }

    @Test
    fun realGesturePassesThroughSafetyCoverAndInstallsTheFlingViewport() {
        val historyStore = PermissionHistoryStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val originalHistory = runBlocking { historyStore.current() }
        runBlocking {
            historyStore.replaceForTesting(
                originalHistory.copy(hasSeenIntroduction = true),
            )
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val mapView = awaitMapView(scenario)
            val mapReady = CountDownLatch(1)
            val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
            scenario.onActivity {
                mapView.getMapAsync { map ->
                    mapRef.set(map)
                    mapReady.countDown()
                }
            }
            assertTrue(mapReady.await(30, TimeUnit.SECONDS))
            val initial = awaitGeneration(mapView, scenario)
            scenario.onActivity {
                requireNotNull(mapRef.get()).moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(25.033, 121.5654), 16f),
                )
            }
            assertTrue(
                awaitTag(mapView, R.id.map_fog_canonical_generation) { value ->
                    value != null && value != initial
                },
            )
            assertTrue(awaitTag(mapView, R.id.map_fog_cover_up) { it == false })
            val beforeFlingGeneration = mapView.getTag(R.id.map_fog_canonical_generation)
            val beforeTouchCount = (mapView.getTag(R.id.map_touch_down_count) as? Int) ?: 0
            val beforeLongitude = AtomicReference<Double>()
            scenario.onActivity {
                beforeLongitude.set(requireNotNull(mapRef.get()).cameraPosition.target.longitude)
            }

            val location = IntArray(2)
            val mapSize = IntArray(2)
            scenario.onActivity {
                mapView.getLocationOnScreen(location)
                mapSize[0] = mapView.width
                mapSize[1] = mapView.height
            }
            val coverObserved = AtomicBoolean(false)
            val coverFrame = AtomicReference<Bitmap?>()
            val polling = Thread {
                repeat(600) {
                    if (mapView.getTag(R.id.map_fog_synchronous_cover_up) == true) {
                        coverObserved.set(true)
                        if (coverFrame.get() == null) {
                            val firstFrame = CountDownLatch(1)
                            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                                Choreographer.getInstance().postFrameCallback {
                                    firstFrame.countDown()
                                }
                            }
                            if (
                                firstFrame.await(1, TimeUnit.SECONDS) &&
                                mapView.getTag(R.id.map_fog_synchronous_cover_up) == true
                            ) {
                                coverFrame.compareAndSet(
                                    null,
                                    InstrumentationRegistry.getInstrumentation()
                                        .uiAutomation
                                        .takeScreenshot(),
                                )
                            }
                        }
                    }
                    Thread.sleep(10L)
                }
            }.apply { start() }
            FlingGestureInjector.flingCameraWest(
                centerX = location[0] + mapSize[0] / 2,
                centerY = location[1] + mapSize[1] / 2,
                screenWidth = mapView.resources.displayMetrics.widthPixels,
            )
            polling.join()

            val afterLongitude = AtomicReference<Double>()
            scenario.onActivity {
                afterLongitude.set(requireNotNull(mapRef.get()).cameraPosition.target.longitude)
            }
            assertTrue(
                "the first fling did not move the camera; touchCount=" +
                    mapView.getTag(R.id.map_touch_down_count) +
                    " before=$beforeLongitude after=$afterLongitude " +
                    "coverObserved=${coverObserved.get()} cover=" +
                    mapView.getTag(R.id.map_fog_cover_up),
                kotlin.math.abs(requireNotNull(afterLongitude.get()) - requireNotNull(beforeLongitude.get())) >
                    0.0001,
            )
            assertTrue(
                "the injected DOWN never reached GestureOwningGoogleMapView",
                ((mapView.getTag(R.id.map_touch_down_count) as? Int) ?: 0) > beforeTouchCount,
            )
            assertTrue("the fling never raised the safety cover", coverObserved.get())
            val capturedCover = coverFrame.get()
            assertNotNull("no screen frame was captured while the cover was up", capturedCover)
            requireNotNull(capturedCover).let { bitmap ->
                try {
                    assertFogCoverPixels(bitmap, location, mapSize)
                } finally {
                    bitmap.recycle()
                }
            }
            assertTrue(
                "the fling viewport never installed a new generation",
                awaitTag(mapView, R.id.map_fog_canonical_generation) { value ->
                    value != null && value != beforeFlingGeneration
                },
            )
            assertTrue(
                "the fling safety cover remained stuck",
                awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
            )
            val coverInterval = mapView.getTag(R.id.map_fog_last_cover_interval_ms) as? Long
            assertNotNull("fling cover interval was not measured", coverInterval)
            assertTrue("fling cover interval must be positive", requireNotNull(coverInterval) > 0L)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "stage6_fling_cover_interval_ms=$coverInterval\n")
                },
            )
            }
        } finally {
            runBlocking { historyStore.replaceForTesting(originalHistory) }
        }
    }

    /**
     * Round-5 finding: both 20 s cover deadlines are armed on a plain main-looper handler with no
     * lifecycle gating, and the fog binding is never told the host stopped. Backgrounding while the
     * cover is up should therefore let the deadline run against a renderer that cannot issue tile
     * requests or serve a snapshot, terminating the primary map. Pocketing the phone is this app's
     * single most common usage pattern, so this asserts the surface survives it.
     */
    @Test
    fun backgroundingWithTheCoverUpDoesNotTerminateTheProductionMap() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val beforeStop = awaitMapView(scenario)
            // Assert rather than assume the precondition. Without this the case passes vacuously
            // whenever the first generation happens to install before the stop: no deadline is
            // armed, so surviving the dwell proves nothing about lifecycle gating.
            assertEquals(
                "the cover was already down before backgrounding, so no bounded deadline was " +
                    "armed and this run did not exercise the lifecycle gate",
                true,
                beforeStop.getTag(R.id.map_fog_cover_up),
            )
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(BACKGROUND_DWELL_MILLIS)
            scenario.moveToState(Lifecycle.State.RESUMED)

            val survived = AtomicBoolean(false)
            scenario.onActivity { activity ->
                survived.set(activity.window.decorView.findMapView() != null)
            }
            assertTrue(
                "backgrounding for ${BACKGROUND_DWELL_MILLIS}ms with the cover up tore the " +
                    "production map down; the user returns to a permanent unavailable surface",
                survived.get(),
            )
            val mapView = awaitMapView(scenario)
            assertNotNull(
                "the map survived backgrounding but never installed canonical fog afterwards",
                awaitGeneration(mapView),
            )
            assertTrue(
                "the safety cover stayed up after returning to the foreground",
                awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
            )
        }
    }

    @Test
    fun tiltedCameraInstallsFromRendererActualLodRequests() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val mapView = awaitMapView(scenario)
            val mapReady = CountDownLatch(1)
            val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
            scenario.onActivity {
                mapView.getMapAsync { map ->
                    mapRef.set(map)
                    mapReady.countDown()
                }
            }
            assertTrue(mapReady.await(30, TimeUnit.SECONDS))
            val initial = awaitGeneration(mapView)
            scenario.onActivity {
                requireNotNull(mapRef.get()).moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(25.033, 121.5654))
                            .zoom(16f)
                            .bearing(35f)
                            .tilt(45f)
                            .build(),
                    ),
                )
            }
            assertTrue(
                "tilted camera reproduced the predicted-floor barrier deadlock",
                awaitTag(mapView, R.id.map_fog_canonical_generation) { value ->
                    value != null && value != initial
                },
            )
            assertTrue(
                "tilted camera left the safety cover stuck",
                awaitTag(mapView, R.id.map_fog_cover_up) { it == false },
            )
        }
    }

    /**
     * Every sample is reported, not just the first mismatch.
     *
     * "pixel N was not fog" cannot distinguish a cover that failed to draw from a frame that captured
     * nothing at all, and those need opposite fixes. The geometry is included for the same reason: a
     * sample can only mean something if it actually landed inside the captured map.
     */
    private fun assertFogCoverPixels(bitmap: Bitmap, origin: IntArray, size: IntArray) {
        val expected = Color.rgb(0x3C, 0x3D, 0x3A)
        val centerX = origin[0] + size[0] / 2
        val centerY = origin[1] + size[1] / 2
        val offsetsX = listOf(-size[0] / 6, 0, size[0] / 6)
        val offsetsY = listOf(-size[1] / 6, 0, size[1] / 6)
        val samples = offsetsY.flatMap { dy ->
            offsetsX.map { dx -> (centerX + dx) to (centerY + dy) }
        }
        val readings = samples.map { (x, y) ->
            val inBitmap = x in 0 until bitmap.width && y in 0 until bitmap.height
            Triple(x to y, inBitmap, if (inBitmap) bitmap[x, y] else 0)
        }
        val mismatched = readings.filter { (_, inBitmap, actual) ->
            !inBitmap ||
                kotlin.math.abs(Color.red(actual) - Color.red(expected)) > 2 ||
                kotlin.math.abs(Color.green(actual) - Color.green(expected)) > 2 ||
                kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) > 2 ||
                Color.alpha(actual) != 255
        }
        assertTrue(
            "safety-cover samples were not opaque fog. expected=#${hex(expected)} " +
                "bitmap=${bitmap.width}x${bitmap.height} config=${bitmap.config} " +
                "mapOrigin=${origin[0]},${origin[1]} mapSize=${size[0]}x${size[1]} " +
                "mismatched=${mismatched.size}/${readings.size} samples=[" +
                readings.joinToString(" ") { (point, inBitmap, actual) ->
                    "${point.first},${point.second}=" +
                        if (inBitmap) "#${hex(actual)}" else "OUT_OF_BITMAP"
                } +
                "]",
            mismatched.isEmpty(),
        )
    }

    private fun hex(color: Int): String = String.format("%08X", color)

    private companion object {
        /** Comfortably past the binding's and the host's 20 s cover deadlines. */
        const val BACKGROUND_DWELL_MILLIS = 25_000L
    }

    private fun awaitGeneration(
        mapView: MapView,
        scenario: ActivityScenario<MainActivity>? = null,
    ): Any {
        repeat(180) {
            mapView.getTag(R.id.map_fog_canonical_generation)?.let { return it }
            Thread.sleep(250L)
        }
        // "never installed" alone cannot tell a surface that never built a binding from one whose
        // binding built and then failed — nor either of those from a test that is watching a view
        // the host has already replaced. All three need different fixes, so name which one it is.
        val live = AtomicReference<MapView>()
        val onScreen = AtomicReference<String>()
        scenario?.onActivity { activity ->
            live.set(activity.window.decorView.findMapView())
            onScreen.set(activity.window.decorView.describeTree())
        }
        val current = live.get()
        error(
            "canonical fog never installed on the production launcher after 45s: " +
                describe("watched", mapView) +
                when {
                    scenario == null -> ""
                    current == null -> " liveMapView=none decorView=[${onScreen.get()}]"
                    current === mapView -> " liveMapView=same"
                    else -> " liveMapView=DIFFERENT " + describe("live", current)
                },
        )
    }

    /** Class names only, so it says what is on screen without reporting anything a user typed. */
    private fun View.describeTree(): String {
        val names = linkedSetOf<String>()
        fun walk(view: View) {
            names += view.javaClass.simpleName.ifEmpty { view.javaClass.name.substringAfterLast('.') }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> walk(view.getChildAt(index)) }
            }
        }
        walk(this)
        return names.joinToString(",")
    }

    private fun describe(label: String, mapView: MapView): String =
        "$label=[binding=${mapView.getTag(R.id.map_fog_binding_state)} " +
            "lastFogFailure=${mapView.getTag(R.id.map_fog_last_failure)} " +
            "basemap=${mapView.getTag(R.id.map_basemap_load_state)} " +
            "generation=${mapView.getTag(R.id.map_fog_canonical_generation)} " +
            "cover=${mapView.getTag(R.id.map_fog_cover_up)} " +
            "syncCover=${mapView.getTag(R.id.map_fog_synchronous_cover_up)} " +
            "attached=${mapView.isAttachedToWindow} shown=${mapView.isShown}]"

    private fun awaitTag(
        mapView: MapView,
        key: Int,
        predicate: (Any?) -> Boolean,
    ): Boolean {
        repeat(180) {
            if (predicate(mapView.getTag(key))) return true
            Thread.sleep(100L)
        }
        return predicate(mapView.getTag(key))
    }

    private fun awaitMapView(scenario: ActivityScenario<MainActivity>): MapView {
        val found = AtomicReference<MapView>()
        repeat(120) {
            scenario.onActivity { activity ->
                found.set(activity.window.decorView.findMapView())
            }
            found.get()?.let { return it }
            Thread.sleep(250L)
        }
        error("real MainActivity did not attach a Google MapView")
    }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }

    private fun View.containsClassName(marker: String): Boolean {
        if (javaClass.name.contains(marker, ignoreCase = true)) return true
        if (this !is ViewGroup) return false
        return (0 until childCount).any { index ->
            getChildAt(index).containsClassName(marker)
        }
    }
}
