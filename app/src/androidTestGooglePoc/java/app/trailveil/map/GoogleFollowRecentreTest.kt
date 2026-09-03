package app.trailveil.map

import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import app.trailveil.googlepoc.FlingGestureInjector
import app.trailveil.map.fog.GeoPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focused Stage-7 camera/overlay smoke cases on the real hosted surface.
 *
 * Bounds-fit detail behavior intentionally remains a Stage-8 case; this class only exercises the
 * neutral recentre/follow contract and the Google overlay handoff inputs.
 */
@RunWith(AndroidJUnit4::class)
class GoogleFollowRecentreTest {
    @Before
    fun setUp() = GoogleMapSurfaceTestHooks.reset()

    @After
    fun tearDown() = GoogleMapSurfaceTestHooks.reset()

    @Test
    fun samePointFollowDoesNotEatRecentreZoom() {
        val target = GeoPoint(25.0330, 121.5654)
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.currentLocation = target
        GoogleMapSurfaceTestHooks.followLocation = target
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(
            requestId = 1L,
            point = target,
            zoom = 16.0,
        )
        val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
        val ready = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.onMapReady.set {
            mapRef.set(it)
            ready.countDown()
        }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            val cameraRef = AtomicReference<com.google.android.gms.maps.model.CameraPosition>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            var camera = readCameraOnMain(it, mapRef, cameraRef)
            while (
                !cameraMatches(camera, target, zoom = 16.0f) &&
                    System.nanoTime() < deadline
            ) {
                Thread.sleep(100L)
                camera = readCameraOnMain(it, mapRef, cameraRef)
            }
            assertEquals(target.latitude, camera.target.latitude, 0.001)
            assertEquals(target.longitude, camera.target.longitude, 0.001)
            assertEquals(16.0f, camera.zoom, 0.1f)
        }
    }

    @Test
    fun datelineTrackInputDoesNotBridgeOrCrashHostedSurface() {
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.trackOverlay = MapTrackOverlay(
            requestId = 7L,
            segments = listOf(
                listOf(
                    GeoPoint(10.0, 179.0),
                    GeoPoint(11.0, -179.0),
                ),
            ),
        )
        val ready = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.onMapReady.set { ready.countDown() }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            Thread.sleep(250L)
        }
    }

    @Test
    fun followMovesAnOffscreenFixOnTheRealHostedSurface() {
        val target = GeoPoint(25.0330, 121.5654)
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
        val ready = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.onMapReady.set {
            mapRef.set(it)
            ready.countDown()
        }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            scenario.onActivity {
                mapRef.get().moveCamera(
                    com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                        com.google.android.gms.maps.model.LatLng(0.0, 0.0),
                        15.0f,
                    ),
                )
                // This state write is on the activity's main thread and causes the real
                // LaunchedEffect(followLocation, cameraRequest) to run after the anchor move.
                GoogleMapSurfaceTestHooks.followLocationState.value = target
            }
            val cameraRef = AtomicReference<com.google.android.gms.maps.model.CameraPosition>()
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var camera = readCameraOnMain(scenario, mapRef, cameraRef)
            while (
                !cameraMatches(camera, target, zoom = null) &&
                    SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(100L)
                camera = readCameraOnMain(scenario, mapRef, cameraRef)
            }
            assertEquals(target.latitude, camera.target.latitude, 0.001)
            assertEquals(target.longitude, camera.target.longitude, 0.001)
        }
    }

    @Test
    fun fogRequiredOverlayStartsHiddenAndRevealsOnlyAfterProductionProof() {
        val initial = GeoPoint(25.0330, 121.5654)
        val later = GeoPoint(25.0340, 121.5664)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val visibilityEvents = CopyOnWriteArrayList<Boolean>()
        val passedProofs = AtomicInteger(0)
        val proofAccepted = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val mapViewRef = AtomicReference<MapView>()

        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.fogRuntime = application.appContainer.fogRuntime()
        GoogleMapSurfaceTestHooks.currentLocation = initial
        GoogleMapSurfaceTestHooks.onMapReady.set { ready.countDown() }
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { mapViewRef.set(it) }
        GoogleMapSurfaceTestHooks.onFogProof.set { observation ->
            if (observation.passed) {
                passedProofs.incrementAndGet()
                proofAccepted.countDown()
            }
        }
        GoogleMapSurfaceTestHooks.onOverlayVisibility.set { visible ->
            visibilityEvents += visible
        }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            assertTrue(
                "production overlay did not publish an initial visibility event",
                awaitUntil { visibilityEvents.isNotEmpty() },
            )
            assertFalse(
                "marker/track became visible before a production fog proof",
                visibilityEvents.first(),
            )
            val proofsBeforeAcceptance = passedProofs.get()
            assertTrue(
                "production fog proof did not pass",
                proofAccepted.await(30, TimeUnit.SECONDS),
            )
            assertTrue(
                "matching proof did not reveal the overlay",
                awaitUntil { visibilityEvents.any { visible -> visible } },
            )
            assertTrue("proof callback did not precede reveal", passedProofs.get() > proofsBeforeAcceptance)

            // A raw active-session fix is a geometry update, not a canonical fog generation. It
            // must keep the proven overlay visible and must not start a new proof per GPS tick.
            val proofsBeforeUpdate = passedProofs.get()
            scenario.onActivity {
                GoogleMapSurfaceTestHooks.currentLocationState.value = later
            }
            SystemClock.sleep(1_000L)
            assertTrue(
                "proven overlay was hidden for a live location update",
                visibilityEvents.last(),
            )
            assertEquals(
                "stable geometry update unexpectedly started another proof",
                proofsBeforeUpdate,
                passedProofs.get(),
            )
            scenario.onActivity {
                assertEquals(
                    true,
                    requireNotNull(mapViewRef.get()).getTag(app.trailveil.R.id.map_overlay_visible),
                )
            }
        }
    }

    @Test
    fun fogFollowInsideCoverageStaysUncoveredAndOutsideFlightProvesBeforeLowering() {
        val initial = GeoPoint(25.0330, 121.5654)
        val far = GeoPoint(-33.8688, 151.2093)
        val midFlightFix = GeoPoint(-33.8600, 151.2100)
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as TrailVeilApplication
        val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
        val mapViewRef = AtomicReference<MapView>()
        val ready = CountDownLatch(1)
        val healthy = CountDownLatch(1)
        val states = CopyOnWriteArrayList<GoogleCanonicalFogState>()
        val passedGenerations = CopyOnWriteArrayList<Long>()

        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.fogRequired = true
        GoogleMapSurfaceTestHooks.fogRuntime = application.appContainer.fogRuntime()
        GoogleMapSurfaceTestHooks.currentLocation = initial
        GoogleMapSurfaceTestHooks.cameraRequestDurationMillis = 1_200
        GoogleMapSurfaceTestHooks.onMapReady.set {
            mapRef.set(it)
            ready.countDown()
        }
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { mapViewRef.set(it) }
        GoogleMapSurfaceTestHooks.onFogState.set { state ->
            states += state
            if (
                state.installedGeneration != null &&
                    state.pendingGeneration == null &&
                    !state.coverUp &&
                    !state.terminal
            ) {
                healthy.countDown()
            }
        }
        GoogleMapSurfaceTestHooks.onFogProof.set { observation ->
            if (observation.passed) passedGenerations += observation.generation
        }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            assertTrue("initial fog generation never became healthy", healthy.await(30, TimeUnit.SECONDS))
            val map = requireNotNull(mapRef.get())
            val mapView = requireNotNull(mapViewRef.get())
            val firstGeneration = requireNotNull(latestHealthyState(states).installedGeneration)

            // Establish a real, label-free anchor for the inside-coverage follow checks.
            scenario.onActivity {
                map.moveCamera(
                    com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(
                        LatLng(initial.latitude, initial.longitude),
                        16.0f,
                    ),
                )
            }
            assertTrue(
                "camera anchor did not install a healthy generation",
                awaitUntil {
                    val state = latestHealthyStateOrNull(states)
                    state != null && state.installedGeneration != firstGeneration
                },
            )
            val stableGeneration = requireNotNull(latestHealthyState(states).installedGeneration)
            assertEquals(false, mapView.getTag(app.trailveil.R.id.map_fog_synchronous_cover_up))

            // A centered fix is HOLD: it must not jitter the camera or raise the cover.
            val center = readCameraOnMain(
                scenario,
                mapRef,
                AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
            ).target
            scenario.onActivity {
                GoogleMapSurfaceTestHooks.followLocationState.value =
                    GeoPoint(center.latitude, center.longitude)
            }
            SystemClock.sleep(700L)
            assertEquals(false, mapView.getTag(app.trailveil.R.id.map_fog_synchronous_cover_up))
            assertEquals(stableGeneration, latestHealthyState(states).installedGeneration)

            // A point outside the dead zone but inside the screen uses the exempt EASE path.
            val easeTarget = AtomicReference<GeoPoint>()
            val beforeEase = center
            scenario.onActivity {
                val offset = minOf(mapView.width, mapView.height) / 5
                val point = map.projection.fromScreenLocation(
                    Point(mapView.width / 2 + offset, mapView.height / 2),
                )
                easeTarget.set(GeoPoint(point.latitude, point.longitude))
                GoogleMapSurfaceTestHooks.followLocationState.value = easeTarget.get()
            }
            assertTrue(
                "follow EASE did not finish",
                awaitUntil(5_000L) {
                    mapView.getTag(app.trailveil.R.id.map_camera_flight_active) == false
                },
            )
            assertTrue(
                "follow EASE did not move the camera",
                awaitUntil {
                    val camera = readCameraOnMain(
                        scenario,
                        mapRef,
                        AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
                    )
                    kotlin.math.abs(camera.target.latitude - beforeEase.latitude) > 0.00001 ||
                        kotlin.math.abs(camera.target.longitude - beforeEase.longitude) > 0.00001
                },
            )
            assertEquals(false, mapView.getTag(app.trailveil.R.id.map_fog_synchronous_cover_up))
            assertEquals(stableGeneration, latestHealthyState(states).installedGeneration)

            // A long programmed request leaves proven coverage. The mid-flight fix is deliberately
            // different; the host ticket must keep follow out of the request and its zoom.
            scenario.onActivity {
                GoogleMapSurfaceTestHooks.cameraRequestState.value = MapCameraRequest(
                    requestId = 12L,
                    point = far,
                    zoom = 12.0,
                )
            }
            assertTrue(
                "outside programmed flight never claimed the host ticket",
                awaitUntil(2_000L) {
                    mapView.getTag(app.trailveil.R.id.map_camera_flight_active) == true
                },
            )
            scenario.onActivity {
                GoogleMapSurfaceTestHooks.followLocationState.value = midFlightFix
            }
            assertTrue(
                "outside flight never raised the synchronous cover",
                awaitUntil(5_000L) {
                    mapView.getTag(app.trailveil.R.id.map_fog_synchronous_cover_up) == true
                },
            )
            assertTrue(
                "outside flight did not land at its requested target/zoom",
                awaitUntil(8_000L) {
                    cameraMatches(
                        readCameraOnMain(
                            scenario,
                            mapRef,
                            AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
                        ),
                        far,
                        zoom = 12.0f,
                    )
                },
            )
            assertTrue(
                "outside flight did not pass a newer generation",
                awaitUntil(30_000L) { passedGenerations.any { it > stableGeneration } },
            )
            assertTrue(
                "outside-flight cover did not stay up until proof/lower",
                awaitUntil(30_000L) {
                    mapView.getTag(app.trailveil.R.id.map_fog_synchronous_cover_up) == false &&
                        mapView.getTag(app.trailveil.R.id.map_fog_cover_up) == false
                },
            )
            val coverStartIndex = states.indexOfFirst { state ->
                state.installedGeneration == stableGeneration && state.coverUp
            }
            val lowerIndex = states.indexOfFirst { state ->
                state.installedGeneration != null &&
                    state.installedGeneration > stableGeneration &&
                    !state.coverUp
            }
            assertTrue("state stream never recorded the outside-flight cover", coverStartIndex >= 0)
            assertTrue(
                "state stream lowered before the newer generation proof",
                lowerIndex > coverStartIndex,
            )
            assertTrue(
                "old G1 state was uncovered before G2 proof/lower",
                states.subList(coverStartIndex, lowerIndex).none { state ->
                    state.installedGeneration == stableGeneration && !state.coverUp
                },
            )
            assertTrue(
                "mid-flight fix consumed the requested recenter zoom",
                cameraMatches(
                    readCameraOnMain(
                        scenario,
                        mapRef,
                        AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
                    ),
                    far,
                    zoom = 12.0f,
                ),
            )
        }
    }

    @Test
    fun secondRecenterSupersedesFirstAndNullZoomPreservesCurrentZoom() {
        val first = GeoPoint(25.0330, 121.5654)
        val second = GeoPoint(-33.8688, 151.2093)
        val third = GeoPoint(40.7128, -74.0060)
        val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
        val mapViewRef = AtomicReference<MapView>()
        val ready = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequestDurationMillis = 1_200
        GoogleMapSurfaceTestHooks.onMapReady.set {
            mapRef.set(it)
            ready.countDown()
        }
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { mapViewRef.set(it) }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            val mapView = requireNotNull(mapViewRef.get())
            scenario.onActivity {
                GoogleMapSurfaceTestHooks.cameraRequestState.value = MapCameraRequest(
                    requestId = 21L,
                    point = first,
                    zoom = 11.0,
                )
            }
            assertTrue(
                "first recenter never claimed the host ticket",
                awaitUntil(2_000L) {
                    mapView.getTag(app.trailveil.R.id.map_camera_flight_active) == true
                },
            )
            scenario.onActivity {
                GoogleMapSurfaceTestHooks.cameraRequestState.value = MapCameraRequest(
                    requestId = 22L,
                    point = second,
                    zoom = 14.0,
                )
            }
            assertTrue(
                "second recenter did not win after the stale first cancel",
                awaitUntil(8_000L) {
                    cameraMatches(
                        readCameraOnMain(
                            scenario,
                            mapRef,
                            AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
                        ),
                        second,
                        zoom = 14.0f,
                    )
                },
            )
            assertTrue(
                "host flight ticket remained active after replacement completed",
                awaitUntil(5_000L) {
                    mapView.getTag(app.trailveil.R.id.map_camera_flight_active) == false
                },
            )
            val zoomBeforeNull = readCameraOnMain(
                scenario,
                mapRef,
                AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
            ).zoom
            scenario.onActivity {
                GoogleMapSurfaceTestHooks.cameraRequestState.value = MapCameraRequest(
                    requestId = 23L,
                    point = third,
                    zoom = null,
                )
            }
            assertTrue(
                "explicit-null zoom request did not recenter",
                awaitUntil(8_000L) {
                    cameraMatches(
                        readCameraOnMain(
                            scenario,
                            mapRef,
                            AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
                        ),
                        third,
                        zoom = null,
                    )
                },
            )
            val zoomAfterNull = readCameraOnMain(
                scenario,
                mapRef,
                AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
            ).zoom
            assertEquals(zoomBeforeNull, zoomAfterNull, 0.1f)
        }
    }

    @Test
    fun programmedMovesDoNotReportGestureButInjectedFlingDoes() {
        val target = GeoPoint(25.0330, 121.5654)
        val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
        val mapViewRef = AtomicReference<MapView>()
        val ready = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(
            requestId = 31L,
            point = target,
            zoom = 16.0,
        )
        GoogleMapSurfaceTestHooks.onMapReady.set {
            mapRef.set(it)
            ready.countDown()
        }
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { mapViewRef.set(it) }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            assertTrue(
                "programmed camera request did not settle",
                awaitUntil(8_000L) {
                    cameraMatches(
                        readCameraOnMain(
                            scenario,
                            mapRef,
                            AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
                        ),
                        target,
                        zoom = 16.0f,
                    )
                },
            )
            assertEquals(0, GoogleMapSurfaceTestHooks.userMovedCount.get())

            val mapView = requireNotNull(mapViewRef.get())
            val before = readCameraOnMain(
                scenario,
                mapRef,
                AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
            ).target
            val origin = IntArray(2)
            scenario.onActivity {
                mapView.getLocationOnScreen(origin)
            }
            FlingGestureInjector.flingCameraWest(
                centerX = origin[0] + mapView.width / 2,
                centerY = origin[1] + mapView.height / 2,
                screenWidth = mapView.resources.displayMetrics.widthPixels,
            )
            assertTrue(
                "injected fling did not dispatch a real gesture callback",
                awaitUntil(5_000L) { GoogleMapSurfaceTestHooks.userMovedCount.get() > 0 },
            )
            assertTrue(
                "injected fling did not move the camera",
                awaitUntil(5_000L) {
                    val after = readCameraOnMain(
                        scenario,
                        mapRef,
                        AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
                    ).target
                    kotlin.math.abs(after.latitude - before.latitude) > 0.0001 ||
                        kotlin.math.abs(after.longitude - before.longitude) > 0.0001
                },
            )
        }
    }

    @Test
    fun sdkOverlayPropertiesAndMarkerTapRemainStable() {
        val current = GeoPoint(25.0330, 121.5654)
        val observation = AtomicReference<GoogleMapOverlayObservation>()
        val observed = CountDownLatch(1)
        val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
        val mapViewRef = AtomicReference<MapView>()
        val ready = CountDownLatch(1)
        GoogleMapSurfaceTestHooks.decision.set(ProviderStartupDecision(true, null))
        GoogleMapSurfaceTestHooks.currentLocation = current
        GoogleMapSurfaceTestHooks.cameraRequest = MapCameraRequest(
            requestId = 41L,
            point = current,
            zoom = 16.0,
        )
        GoogleMapSurfaceTestHooks.trackOverlay = MapTrackOverlay(
            requestId = 42L,
            segments = listOf(
                listOf(GeoPoint(25.0320, 121.5640), GeoPoint(25.0340, 121.5660)),
                listOf(GeoPoint(25.0400, 121.5700)),
                listOf(GeoPoint(10.0, 179.0), GeoPoint(11.0, -179.0)),
            ),
        )
        GoogleMapSurfaceTestHooks.onMapReady.set {
            mapRef.set(it)
            ready.countDown()
        }
        GoogleMapSurfaceTestHooks.onMapViewCreated.set { mapViewRef.set(it) }
        GoogleMapSurfaceTestHooks.onOverlayObservation.set { value ->
            observation.set(value)
            if (
                value.currentMarker?.visible == true &&
                    value.polylines.size == 3 &&
                    value.trackMarkers.size == 1
            ) {
                observed.countDown()
            }
        }

        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            assertTrue("Google map did not become ready", ready.await(30, TimeUnit.SECONDS))
            assertTrue("SDK overlay observation never arrived", observed.await(10, TimeUnit.SECONDS))
            val value = requireNotNull(observation.get())
            val marker = requireNotNull(value.currentMarker)
            assertEquals(current.latitude, marker.position.latitude, 0.001)
            assertEquals(current.longitude, marker.position.longitude, 0.001)
            assertTrue(marker.visible)
            assertEquals(null, marker.title)
            assertEquals(null, marker.snippet)
            assertEquals(1, value.trackMarkers.size)
            assertEquals(25.0400, value.trackMarkers.single().position.latitude, 0.001)
            assertTrue(value.trackMarkers.single().visible)

            val expectedColor = Color.argb(229, 0x6A, 0x1B, 0x9A)
            assertEquals(3, value.polylines.size)
            value.polylines.forEach { line ->
                assertEquals(expectedColor, line.color)
                assertEquals(229, line.alpha)
                assertEquals(5.0f, line.width, 0.0f)
                assertEquals(Float.MAX_VALUE, line.zIndex, 0.0f)
                assertFalse(line.geodesic)
                assertTrue(line.visible)
                assertTrue(
                    "polyline bridged more than half the world: $line",
                    line.points.zipWithNext().all { (a, b) ->
                        kotlin.math.abs(b.longitude - a.longitude) <= 180.0
                    },
                )
            }
            val datelineLines = value.polylines.filter { line ->
                line.points.any { point -> kotlin.math.abs(kotlin.math.abs(point.longitude) - 180.0) < 0.001 }
            }
            assertEquals(2, datelineLines.size)
            assertEquals(180.0, datelineLines[0].points.last().longitude, 0.001)
            assertEquals(-180.0, datelineLines[1].points.first().longitude, 0.001)

            val map = requireNotNull(mapRef.get())
            val mapView = requireNotNull(mapViewRef.get())
            // This fixture arms BOTH one-shot camera moves the surface can make: the camera request
            // above, and the detail bounds fit that any trackOverlay triggers on a fogRequired=false
            // surface. Production never pairs them — the history detail map passes a trackOverlay and
            // no camera request, and the live map passes a camera request with fogRequired=true,
            // which returns at fitLoadedDetailMap's first guard. So which of the two lands last is a
            // race this test invented, and the fit's postDelayed layout retry makes it load-dependent:
            // under a full suite the fit landed after the settle check and the marker-tap assertion
            // reported the track's own bounds centre as "the tap moved the camera".
            // Wait for the fit's preconditions, let both one-shots finish, then put the camera where
            // this assertion actually needs it. The bounds fit is armed by OnMapLoadedCallback
            // (map_detail_map_loaded) and issued only once the view is laid out with a size, so
            // both are waited for: quiescence measured before that would be measuring a map that
            // has not yet been given its reason to move.
            assertTrue(
                "the surface never reported the detail map loaded on a laid-out view, so the " +
                    "one-shot fit never had its preconditions and quiescence would be vacuous",
                awaitUntil(30_000L) {
                    mapView.getTag(app.trailveil.R.id.map_detail_map_loaded) == true &&
                        mapView.isLaidOut && mapView.width > 0 && mapView.height > 0
                },
            )
            assertTrue(
                "the surface never stopped moving the camera on its own",
                awaitCameraQuiescent(scenario, mapRef),
            )
            scenario.onActivity {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(current.latitude, current.longitude),
                        16.0f,
                    ),
                )
            }
            assertTrue(
                "camera was not settled before marker tap",
                awaitUntil(8_000L) {
                    cameraMatches(
                        readCameraOnMain(
                            scenario,
                            mapRef,
                            AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
                        ),
                        current,
                        zoom = 16.0f,
                    )
                },
            )
            val before = readCameraOnMain(
                scenario,
                mapRef,
                AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
            )
            val markerScreen = AtomicReference<Point>()
            val origin = IntArray(2)
            scenario.onActivity {
                markerScreen.set(map.projection.toScreenLocation(marker.position))
                mapView.getLocationOnScreen(origin)
            }
            tapScreen(
                x = origin[0] + requireNotNull(markerScreen.get()).x,
                y = origin[1] + requireNotNull(markerScreen.get()).y,
            )
            SystemClock.sleep(500L)
            val after = readCameraOnMain(
                scenario,
                mapRef,
                AtomicReference<com.google.android.gms.maps.model.CameraPosition>(),
            )
            assertEquals(before.target.latitude, after.target.latitude, 0.001)
            assertEquals(before.target.longitude, after.target.longitude, 0.001)
            assertEquals(before.zoom, after.zoom, 0.1f)
            assertTrue(requireNotNull(observation.get()).currentMarker?.visible == true)
            scenario.onActivity {
                assertFalse(map.uiSettings.isMapToolbarEnabled)
                assertFalse(mapView.containsClassName("InfoWindow"))
                assertFalse(mapView.containsClassName("MapToolbar"))
            }
        }
    }

    private fun latestHealthyState(states: List<GoogleCanonicalFogState>): GoogleCanonicalFogState =
        requireNotNull(latestHealthyStateOrNull(states)) {
            "no healthy installed Google fog state was observed: $states"
        }

    private fun latestHealthyStateOrNull(
        states: List<GoogleCanonicalFogState>,
    ): GoogleCanonicalFogState? = states.lastOrNull { state ->
        state.installedGeneration != null &&
            state.pendingGeneration == null &&
            !state.coverUp &&
            !state.terminal
    }

    private fun awaitUntil(timeoutMillis: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50L)
        }
        return condition()
    }

    private fun tapScreen(x: Int, y: Int) {
        bestEffortClearStuckInjectedPointers()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        var streamEnded = false
        fun inject(action: Int, eventTime: Long): Boolean {
            val event = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            return try {
                automation.injectInputEvent(event, true)
            } finally {
                event.recycle()
            }
        }
        try {
            check(inject(MotionEvent.ACTION_DOWN, downTime)) {
                "marker DOWN was rejected at screen=($x,$y)"
            }
            check(inject(MotionEvent.ACTION_UP, downTime + 20L)) {
                "marker UP was rejected at screen=($x,$y)"
            }
            streamEnded = true
        } finally {
            if (!streamEnded) bestEffortClearStuckInjectedPointers()
        }
    }

    /**
     * True once the surface has stopped moving the camera by itself.
     *
     * A settle check that only asks "is the camera where I asked for it" cannot see a second
     * one-shot move still queued behind the first, so it passes and then loses the race.
     */
    private fun awaitCameraQuiescent(
        scenario: ActivityScenario<GoogleMapSurfaceTestActivity>,
        mapRef: AtomicReference<com.google.android.gms.maps.GoogleMap>,
        timeoutMillis: Long = 20_000L,
        stableSamples: Int = 4,
    ): Boolean {
        val cameraRef = AtomicReference<com.google.android.gms.maps.model.CameraPosition>()
        var previous: com.google.android.gms.maps.model.CameraPosition? = null
        var stable = 0
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = readCameraOnMain(scenario, mapRef, cameraRef)
            val last = previous
            stable = if (
                last != null &&
                cameraMatches(now, GeoPoint(last.target.latitude, last.target.longitude), last.zoom)
            ) {
                stable + 1
            } else {
                0
            }
            previous = now
            if (stable >= stableSamples) return true
            SystemClock.sleep(250L)
        }
        return false
    }

    private fun readCameraOnMain(
        scenario: ActivityScenario<GoogleMapSurfaceTestActivity>,
        mapRef: AtomicReference<com.google.android.gms.maps.GoogleMap>,
        cameraRef: AtomicReference<com.google.android.gms.maps.model.CameraPosition>,
    ): com.google.android.gms.maps.model.CameraPosition {
        scenario.onActivity { cameraRef.set(requireNotNull(mapRef.get()).cameraPosition) }
        return requireNotNull(cameraRef.get())
    }

    private fun cameraMatches(
        camera: com.google.android.gms.maps.model.CameraPosition,
        target: GeoPoint,
        zoom: Float?,
    ): Boolean =
        kotlin.math.abs(camera.target.latitude - target.latitude) <= 0.001 &&
            kotlin.math.abs(camera.target.longitude - target.longitude) <= 0.001 &&
            (zoom == null || kotlin.math.abs(camera.zoom - zoom) <= 0.1f)

    private fun View.containsClassName(marker: String): Boolean {
        if (javaClass.name.contains(marker, ignoreCase = true)) return true
        if (this !is ViewGroup) return false
        return (0 until childCount).any { index ->
            getChildAt(index).containsClassName(marker)
        }
    }
}
