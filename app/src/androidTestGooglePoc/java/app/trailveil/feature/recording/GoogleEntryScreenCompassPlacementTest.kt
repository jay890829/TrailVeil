package app.trailveil.feature.recording

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.googlepoc.SpikeCaptureSupport
import app.trailveil.googlepoc.SpikeScenarioSupport
import app.trailveil.map.GoogleMapSurfaceTestActivity
import app.trailveil.map.GoogleMapSurfaceTestHooks
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * `V02-007`: the Google twin of
 * `RecordingEntryScreenTest#theCompassSitsBelowTheMenuAndNoNoticeReachesIt`.
 *
 * The MapLibre original drives the production entry screen with its longest notice card, finds the
 * SDK compass, and asserts two things about where it landed: its top is at or below the menu
 * button's bottom, and the widest notice's right edge stays left of it. Those are the two ways the
 * screen's own controls can end up on top of a control the map owns.
 *
 * The property is the same here; the mechanism is not. MapLibre exposes
 * `uiSettings.setCompassMargins`, and the neutral surface signature carries `compassTopInset` and
 * `compassEndInset` for exactly that call. The Maps SDK has no equivalent: its only documented lever
 * is `GoogleMap.setPadding`, which this repository does not use, because padding moves the logical
 * camera centre and every fog viewport calculation is built on that centre. So the Google actual
 * places the SDK's own compass view instead, the same way `GoogleFogSafetyOverlay` already lifts the
 * SDK attribution ImageView above the navigation bar: move the view, touch nothing the camera reads.
 *
 * Three deltas from the original, all forced and all recorded rather than smoothed over.
 *
 * 1. **The camera must carry a bearing.** MapLibre's compass fades at north but stays laid out, so
 *    the original can measure it at rest. The Maps SDK gives its compass no size until the camera is
 *    rotated or tilted, so at a north-up camera there is nothing to measure. This rotates first and
 *    says so; a test measuring a zero-size view would assert nothing.
 * 2. **The compass is located, not queried.** There is no public accessor for the view. The locator
 *    written for the spike work is reused, and the strategy it used is reported in every failure, so
 *    a run that fell back to a guessed rectangle cannot be mistaken for a measurement - it abstains.
 * 3. **The screen is hosted on this variant's own harness activity.** `createComposeRule` launches
 *    `androidx.activity.ComponentActivity` out of the test apk, and instrumentation targeting
 *    `app.trailveil` cannot start an activity that resolves in `app.trailveil.test`. Making it
 *    resolve app-side would mean putting `compose-ui-test-manifest` into the googlePoc build type,
 *    which `V02-008` ships. `GoogleMapSurfaceTestActivity` is already this variant's unexported
 *    harness host, so it hosts the screen instead: the same composable, the same theme, the same
 *    window, through the content hook that activity now carries.
 *
 * Abstains rather than passes when the key is not configured or the SDK never lays the compass out.
 */
@RunWith(AndroidJUnit4::class)
class GoogleEntryScreenCompassPlacementTest {
    /**
     * Ordered before the activity rule, because the activity reads the hook in `onCreate` and a
     * `@Before` method runs after the rule has already launched it.
     */
    @get:Rule(order = 0)
    val hostContent = object : ExternalResource() {
        override fun before() {
            GoogleMapSurfaceTestHooks.reset()
            GoogleMapSurfaceTestHooks.content.set(entryScreen)
        }

        override fun after() = GoogleMapSurfaceTestHooks.reset()
    }

    @get:Rule(order = 1)
    internal val composeRule = createAndroidComposeRule<GoogleMapSurfaceTestActivity>()

    private val entryScreen: @Composable () -> Unit = {
        RecordingEntryScreen(
            state = RecordingEntryUiState(
                firstVisit = false,
                // The longest notice this screen has, so a card that wraps is the one measured.
                locationNotice = LocationNotice.PRECISE_SETTINGS,
                canRecenter = true,
            ),
            onStart = {},
            onStop = {},
            onLocationAction = {},
            onDismissLocationNotice = {},
            onNotificationAction = {},
        )
    }

    @Test
    fun theCompassSitsBelowTheMenuAndNoNoticeReachesIt() {
        SpikeScenarioSupport.assumeKeyConfigured()
        composeRule.onNodeWithTag(RecordingEntryTestTags.LocationNotice).assertIsDisplayed()
        val mapView = awaitMapView()
        val map = awaitMap(mapView)
        // Delta 1: without a bearing the SDK compass has no size, so there is nothing to place or
        // to measure. The camera a user rotates is the camera this asserts on.
        onMain {
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder(map.cameraPosition)
                        .bearing(COMPASS_REVEAL_BEARING)
                        .build(),
                ),
            )
        }

        val compass = awaitCompass(mapView)
        val mapOrigin = onMain {
            IntArray(2).also { location -> mapView.getLocationInWindow(location) }
        }
        val compassTopInWindow = mapOrigin[1] + compass.boundsInMapViewPx.top
        val compassLeftInWindow = mapOrigin[0] + compass.boundsInMapViewPx.left
        val menu = composeRule.onNodeWithTag(RecordingEntryTestTags.Menu)
            .getUnclippedBoundsInRoot()
        val notice = composeRule.onNodeWithTag(RecordingEntryTestTags.LocationNotice)
            .getUnclippedBoundsInRoot()
        val density = composeRule.density
        val describe = "compass=[${compass.strategy} ${compass.viewClass} " +
            "${compass.boundsInMapViewPx} visible=${compass.visible} " +
            "path=${compass.hierarchyPath}] mapOriginInWindow=${mapOrigin[0]},${mapOrigin[1]}"

        with(density) {
            assertTrue(
                "The compass (top=${compassTopInWindow}px in the window) is not below the menu " +
                    "button (bottom=${menu.bottom.roundToPx()}px), so the screen's own control " +
                    "sits on top of the map's. $describe",
                compassTopInWindow >= menu.bottom.roundToPx(),
            )
            assertTrue(
                "A notice reaches ${notice.right.roundToPx()}px, past the compass's left edge at " +
                    "${compassLeftInWindow}px. $describe",
                notice.right.roundToPx() <= compassLeftInWindow,
            )
        }
    }

    private fun awaitCompass(mapView: MapView): SpikeCaptureSupport.LocatorObservation {
        var last: SpikeCaptureSupport.LocatorObservation? = null
        composeRule.waitUntil(timeoutMillis = COMPASS_TIMEOUT_MILLIS) {
            val observation = onMain { SpikeCaptureSupport.locateCompass(mapView) }
            last = observation
            observation.found &&
                observation.boundsInMapViewPx.width() > 0 &&
                observation.boundsInMapViewPx.height() > 0
        }
        val observation = requireNotNull(last)
        assumeTrue(
            "the Maps SDK never laid its compass out on this device, so there is no placement to " +
                "measure; abstaining rather than asserting on a guessed rectangle " +
                "(strategy=${observation.strategy} bounds=${observation.boundsInMapViewPx})",
            observation.strategy != FALLBACK_STRATEGY,
        )
        return observation
    }

    private fun awaitMapView(): MapView {
        val holder = AtomicReference<MapView>()
        composeRule.waitUntil(timeoutMillis = MAP_TIMEOUT_MILLIS) {
            val found = onMain { composeRule.activity.window.decorView.findMapView() }
            holder.set(found)
            found != null && found.width > 0 && found.height > 0
        }
        return requireNotNull(holder.get())
    }

    private fun awaitMap(mapView: MapView): GoogleMap {
        val ready = CountDownLatch(1)
        val holder = AtomicReference<GoogleMap>()
        onMain {
            mapView.getMapAsync { map ->
                holder.set(map)
                ready.countDown()
            }
        }
        assertTrue(
            "the entry screen's Google map never became ready",
            ready.await(MAP_TIMEOUT_MILLIS / 1000, TimeUnit.SECONDS),
        )
        // The rotation below is only meaningful once the SDK has a laid-out surface to rotate.
        SystemClock.sleep(MAP_SETTLE_MILLIS)
        return requireNotNull(holder.get())
    }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }

    private fun <T> onMain(block: () -> T): T {
        val holder = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.set(block()) }
        @Suppress("UNCHECKED_CAST")
        return holder.get() as T
    }

    private companion object {
        const val COMPASS_REVEAL_BEARING = 45f
        const val COMPASS_TIMEOUT_MILLIS = 20_000L
        const val MAP_TIMEOUT_MILLIS = 30_000L
        const val MAP_SETTLE_MILLIS = 1_500L
        const val FALLBACK_STRATEGY = "fallbackRect"
    }
}
