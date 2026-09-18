package app.trailveil.map

import android.Manifest
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.FogTileColor
import android.graphics.Bitmap
import android.graphics.Color
import android.content.pm.PackageManager
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
import app.trailveil.feature.recording.PermissionHistory
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
            // Installation precedes the snapshot verdict; V02-012 deliberately holds the cover
            // across that interval. Wait for the existing proof gate before auditing interaction.
            assertTrue("first installed generation never passed its cover-held proof",
                awaitTag(mapView, R.id.map_fog_cover_up) { it == false })
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
        // This case asserts real screen pixels over a full-bleed map, so every card the entry
        // screen may draw is part of its oracle. Copying the stored history would inherit
        // `hasRequestedNotifications` from whatever ran before, and with POST_NOTIFICATIONS denied
        // - the state P4-046 requires the host to prepare - the state machine then reports
        // DENIED_OPEN_SETTINGS and the app correctly draws its notification notice across the map.
        // Measured: 5 of 9 samples off fog, two of them the notice's own #FFFFD8E4. Pin the whole
        // profile the way `GoogleGestureExposureTest` does rather than inherit one.
        runBlocking {
            historyStore.replaceForTesting(
                PermissionHistory(
                    hasSeenIntroduction = true,
                    hasRequestedLocation = true,
                    hasRetriedLocation = true,
                    hasRequestedPreciseUpgrade = true,
                    hasRequestedNotifications = false,
                ),
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
            // The cover frame below is a full-screen capture over a full-bleed map, so any card the
            // entry screen draws lands inside its samples. A completed result's card expires on its
            // own; a failed or interrupted one persists until acknowledged. Use the card's real
            // dismiss action - no Room data is touched - and then require the fixture to be clear,
            // naming whatever is left, exactly as `GestureExposureAudit` does for the same reason.
            var terminalAcknowledged = false
            scenario.onActivity { terminalAcknowledged = acknowledgeMapAuditTerminalNotice(mapView) }
            var liveNotices = emptySet<String>()
            val fixtureIsClear = run {
                repeat(100) {
                    scenario.onActivity { liveNotices = liveMapAuditNotices(mapView) }
                    if (liveNotices.isEmpty()) return@run true
                    Thread.sleep(100L)
                }
                false
            }
            assertTrue(
                "this map-only fixture contains unexpected notice cards: $liveNotices " +
                    "(terminalAcknowledged=$terminalAcknowledged, ${notificationPrecondition()})",
                fixtureIsClear,
            )
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
                            // The cover is a View drawable and the basemap is the SDK's own
                            // renderer; the two are not presented in the same frame.
                            // `GoogleFogSafetyOverlay` measured that seam from the other side - "the
                            // first frame after `lower` read about 11 % bare basemap, the next was
                            // whole" - and compensates with its LOWER_SETTLE_MILLIS. Raising has the
                            // same seam, and this capture was taking the first frame: measured at one
                            // pose, a settled covered frame is 89.7 % inside the fog window with no
                            // pixel above luminance 250, while a first-frame capture read 81.8 % with
                            // 1.07 % above it - undimmed SDK labels that the next frame covers. Wait
                            // for the frame after, which is the overlay's own remedy.
                            val settledFrame = CountDownLatch(1)
                            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                                Choreographer.getInstance().postFrameCallback {
                                    Choreographer.getInstance().postFrameCallback {
                                        settledFrame.countDown()
                                    }
                                }
                            }
                            if (
                                settledFrame.await(1, TimeUnit.SECONDS) &&
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
            var noticesAtCapture = emptySet<String>()
            scenario.onActivity { noticesAtCapture = liveMapAuditNotices(mapView) }
            requireNotNull(capturedCover).let { bitmap ->
                try {
                    assertFogCoverPixels(
                        bitmap,
                        location,
                        mapSize,
                        "${notificationPrecondition()} liveNotices=$noticesAtCapture",
                    )
                } catch (failure: AssertionError) {
                    // Colour names alone have now sent two plausible causes to the bin. Keep the
                    // exact frame that was judged, plus the screen as it stands when the judgement
                    // failed, so the next step is looking rather than guessing.
                    throw AssertionError(
                        "${failure.message} coverFrame=${saveFrame(bitmap, "fling-cover")} " +
                            "screenNow=${
                                saveFrame(
                                    InstrumentationRegistry.getInstrumentation()
                                        .uiAutomation
                                        .takeScreenshot(),
                                    "fling-screen-after",
                                )
                            }",
                    )
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
            // Assert rather than assume the precondition - but ARRANGE it first. Asserting alone
            // made the outcome depend on whether the first generation happened to finish before
            // the stop, which is a property of the hardware, not of the gate: on the owner's phone
            // the cover is already down by the time awaitMapView returns, so the case failed there
            // while passing 3 of 3 when its class ran alone. Raising a fresh cover makes the
            // precondition deterministic without weakening it - a run that still cannot arm one
            // fails, and says so.
            assertTrue(
                "no safety cover could be raised before backgrounding, so no bounded deadline was " +
                    "armed and this run did not exercise the lifecycle gate: " +
                    describe("watched", beforeStop),
                armAFreshCover(scenario, beforeStop),
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
     * The notification precondition travels with the failure on purpose: a notice card drawn over
     * the map reads as ordinary off-fog pixels, and naming the state that produces it turns that
     * into a one-line diagnosis instead of an A/B run.
     */
    /** Failure-only: the judged frame is the evidence, and colour names have not been enough. */
    private fun saveFrame(bitmap: Bitmap, label: String): String = runCatching {
        val directory = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getExternalFilesDir(null)
        val file = java.io.File(directory, "$label.png")
        file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        file.absolutePath
    }.getOrElse { "unsaved:${it.javaClass.simpleName}" }

    private fun notificationPrecondition(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val history = runBlocking { PermissionHistoryStore(context).current() }
        return "notificationsGranted=$granted " +
            "hasRequestedNotifications=${history.hasRequestedNotifications}"
    }

    /**
     * Every sample is reported, not just the first mismatch.
     *
     * "pixel N was not fog" cannot distinguish a cover that failed to draw from a frame that captured
     * nothing at all, and those need opposite fixes. The geometry is included for the same reason: a
     * sample can only mean something if it actually landed inside the captured map. The notification
     * precondition rides along because a notice card over the map is indistinguishable from bare
     * basemap at the pixel level.
     */
    private fun assertFogCoverPixels(
        bitmap: Bitmap,
        origin: IntArray,
        size: IntArray,
        precondition: String,
    ) {
        // V02-012 design 2: the cover is the default fog colour at the shared fog opacity, so a
        // covered pixel is fog blended over the basemap - the codec's revealed-fog window. Labels
        // and icons draw beneath the cover (dimmed, never bare), so the rule is the prover's
        // 5-of-9, not all nine.
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
                !FogTilePngCodec.matchesBeneathCover(
                    FogTileColor(Color.red(actual), Color.green(actual), Color.blue(actual)),
                    2,
                )
        }
        assertTrue(
            "safety-cover samples were not fog over basemap. window=" +
                "r${FogTilePngCodec.revealedFogChannelRange(FogTilePngCodec.DEFAULT_FOG_COLOR.red, 2)} " +
                "g${FogTilePngCodec.revealedFogChannelRange(FogTilePngCodec.DEFAULT_FOG_COLOR.green, 2)} " +
                "b${FogTilePngCodec.revealedFogChannelRange(FogTilePngCodec.DEFAULT_FOG_COLOR.blue, 2)} " +
                "bitmap=${bitmap.width}x${bitmap.height} config=${bitmap.config} " +
                "mapOrigin=${origin[0]},${origin[1]} mapSize=${size[0]}x${size[1]} " +
                "$precondition " +
                "mismatched=${mismatched.size}/${readings.size} (at most ${readings.size - COVER_STRONG_MATCHES} allowed) samples=[" +
                readings.joinToString(" ") { (point, inBitmap, actual) ->
                    "${point.first},${point.second}=" +
                        if (inBitmap) "#${hex(actual)}" else "OUT_OF_BITMAP"
                } +
                "]",
            readings.size - mismatched.size >= COVER_STRONG_MATCHES,
        )
    }

    private fun hex(color: Int): String = String.format("%08X", color)

    private companion object {
        /** Comfortably past the binding's and the host's 20 s cover deadlines. */
        const val BACKGROUND_DWELL_MILLIS = 25_000L

        /** The prover's strong-neighbourhood rule: 5 of 9 samples read as fog over basemap. */
        const val COVER_STRONG_MATCHES = 5
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

    /**
     * Raise a safety cover and return once it is observed up, so the caller can background the host
     * while a bounded deadline is actually armed.
     *
     * The cover and its 20 s deadline are armed on the *rising* edge of a generation, so this drives
     * one rather than waiting for the app to produce one on its own. A move to a low zoom is chosen
     * deliberately: a wide viewport is this app's expensive raster, which holds the cover up long
     * enough to be seen at a 50 ms poll instead of racing a sub-frame window. Two zooms alternate so
     * a second attempt is a real camera change rather than a no-op the coordinator can coalesce.
     */
    private fun armAFreshCover(
        scenario: ActivityScenario<MainActivity>,
        mapView: MapView,
    ): Boolean {
        if (mapView.getTag(R.id.map_fog_cover_up) == true) return true
        val mapReady = CountDownLatch(1)
        val mapRef = AtomicReference<com.google.android.gms.maps.GoogleMap>()
        scenario.onActivity {
            mapView.getMapAsync { map ->
                mapRef.set(map)
                mapReady.countDown()
            }
        }
        if (!mapReady.await(30, TimeUnit.SECONDS)) return false
        repeat(4) { attempt ->
            scenario.onActivity {
                requireNotNull(mapRef.get()).moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(25.033, 121.5654),
                        if (attempt % 2 == 0) 4f else 5f,
                    ),
                )
            }
            repeat(100) {
                if (mapView.getTag(R.id.map_fog_cover_up) == true) return true
                Thread.sleep(50L)
            }
        }
        return mapView.getTag(R.id.map_fog_cover_up) == true
    }

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
