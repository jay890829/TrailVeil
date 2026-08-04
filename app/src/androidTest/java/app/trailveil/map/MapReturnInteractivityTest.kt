package app.trailveil.map

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.trailveil.MainActivity
import app.trailveil.feature.history.RecordingHistoryTestTags
import app.trailveil.feature.recording.RecordingEntryTestTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * The main map is the screen the user comes back to, so it has to be usable when they arrive.
 * This measures the real thing they complained about: how long after returning from history the
 * first drag is still ignored.
 */
@RunWith(AndroidJUnit4::class)
class MapReturnInteractivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theMapPansOnTheFirstDragAfterReturningFromHistory() {
        dismissDisclosureIfShown()
        val map = requireNotNull(awaitMap()) { "The map never became ready" }
        composeRule.waitForIdle()

        // Control: the same loop before navigating anywhere, so the measurement below is about
        // returning from history rather than about how long a drag takes to register at all.
        val controlBefore = requireNotNull(cameraTarget())
        var controlAttempts = 0
        while (controlAttempts < CONTROL_ATTEMPT_LIMIT) {
            controlAttempts += 1
            drag()
            if (movedFrom(controlBefore)) break
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyIsShowing() }

        Espresso.pressBack()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(RecordingEntryTestTags.Menu)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val before = requireNotNull(cameraTarget()) { "No camera position after returning" }
        // The cover is deliberately still up here: fog has not been rebuilt for this viewport yet,
        // and hiding it early is what P4-008 forbids. What must not happen is the cover eating the
        // gesture, so the measurement starts while it is still on screen.
        val coverUp = composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
            .fetchSemanticsNodes()
            .isNotEmpty()
        val start = SystemClock.uptimeMillis()
        var attempts = 0
        var deadMillis = -1L
        while (SystemClock.uptimeMillis() - start < INTERACTIVE_TIMEOUT_MILLIS) {
            attempts += 1
            drag()
            if (movedFrom(before)) {
                deadMillis = SystemClock.uptimeMillis() - start
                break
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString(
                    "stream",
                    "TrailVeil return-from-history interactivity: deadMillis=$deadMillis " +
                        "attempts=$attempts controlAttempts=$controlAttempts coverStillUp=$coverUp " +
                        "budget=$INTERACTIVE_BUDGET_MILLIS zoom=${map.cameraPosition.zoom}\n",
                )
            },
        )
        assertTrue(
            "The map never accepted a drag after returning from history",
            deadMillis >= 0L,
        )
        // `controlAttempts` is reported rather than asserted: it says how responsive this device
        // was before navigation entered the picture, which is context for reading the number
        // below, not a property of the code under test.
        assertTrue(
            "Drags after returning were ignored for ${deadMillis}ms ($attempts attempts, " +
                "control took $controlAttempts)",
            deadMillis <= INTERACTIVE_BUDGET_MILLIS,
        )
    }

    private fun dismissDisclosureIfShown() {
        composeRule.waitForIdle()
        repeat(DISCLOSURE_POLLS) {
            val sheet = composeRule.onAllNodesWithTag(RecordingEntryTestTags.PrivacySheet)
                .fetchSemanticsNodes()
            if (sheet.isNotEmpty()) {
                composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacyDismiss).performClick()
                composeRule.waitForIdle()
                return
            }
            SystemClock.sleep(POLL_MILLIS)
        }
    }

    private fun historyIsShowing(): Boolean = listOf(
        RecordingHistoryTestTags.Loading,
        RecordingHistoryTestTags.Empty,
        RecordingHistoryTestTags.List,
    ).any { tag ->
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun movedFrom(before: LatLng): Boolean {
        val now = cameraTarget() ?: return false
        return Math.abs(now.latitude - before.latitude) > MOVEMENT_TOLERANCE_DEGREES ||
            Math.abs(now.longitude - before.longitude) > MOVEMENT_TOLERANCE_DEGREES
    }

    private fun cameraTarget(): LatLng? = composeRule.runOnIdle {
        attachedMapView()?.let { view ->
            val found = AtomicReference<LatLng?>(null)
            view.getMapAsync { map -> found.set(map.cameraPosition.target) }
            found.get()
        }
    }

    private fun drag() {
        val view = composeRule.runOnIdle { attachedMapView() } ?: return
        val location = IntArray(2)
        composeRule.runOnIdle { view.getLocationOnScreen(location) }
        val x = (location[0] + view.width / 2).toFloat()
        val fromY = (location[1] + view.height * 2 / 3).toFloat()
        val toY = (location[1] + view.height / 3).toFloat()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, fromY, 0),
        )
        repeat(DRAG_STEPS) { step ->
            SystemClock.sleep(DRAG_STEP_MILLIS)
            val y = fromY + (toY - fromY) * (step + 1) / DRAG_STEPS
            instrumentation.sendPointerSync(
                MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    0,
                ),
            )
        }
        instrumentation.sendPointerSync(
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                x,
                toY,
                0,
            ),
        )
    }

    private fun awaitMap(): MapLibreMap? {
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            composeRule.runOnIdle { attachedMapView() } != null
        }
        val mapView = requireNotNull(composeRule.runOnIdle { attachedMapView() })
        val ready = CountDownLatch(1)
        val found = AtomicReference<MapLibreMap?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getMapAsync { map ->
                found.set(map)
                ready.countDown()
            }
        }
        ready.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return found.get()
    }

    private fun attachedMapView(): MapView? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstNotNullOfOrNull { activity -> activity.window.decorView.findMapView() }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findMapView()?.let { return it }
        }
        return null
    }

    private companion object {
        const val NAVIGATION_TIMEOUT_MILLIS = 20_000L
        const val INTERACTIVE_TIMEOUT_MILLIS = 20_000L

        /**
         * Measured 3,234 ms before the safety cover stopped consuming touches, and 628–1,033 ms
         * after it across three consecutive runs. The remainder is a genuinely new `MapView`:
         * navigation discards the destination, so its GL surface has to be created again before
         * MapLibre's gesture detector exists at all. Closing that gap means keeping the map alive
         * across navigation, which is still open work — this budget guards the part already won.
         */
        const val INTERACTIVE_BUDGET_MILLIS = 1_500L
        const val MAP_READY_TIMEOUT_SECONDS = 20L
        const val MOVEMENT_TOLERANCE_DEGREES = 1e-6
        const val DRAG_STEPS = 6
        const val DRAG_STEP_MILLIS = 16L
        const val CONTROL_ATTEMPT_LIMIT = 10
        const val DISCLOSURE_POLLS = 40
        const val POLL_MILLIS = 100L
    }
}
