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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

        // Prime the cold MapLibre surface and the input injector before navigation. Cold-start
        // readiness is not this task's subject, so the control may retry; the return path below may
        // not. That preserves the distinction between harness setup and the lost-first-drag defect.
        var controlBefore = awaitStableCameraTarget(map)
        var controlAttempts = 0
        var controlMoved = false
        val controlStart = SystemClock.uptimeMillis()
        while (controlAttempts < CONTROL_ATTEMPT_LIMIT && !controlMoved) {
            controlAttempts += 1
            drag()
            controlMoved = runCatching {
                composeRule.waitUntil(CONTROL_DRAG_RESULT_TIMEOUT_MILLIS) {
                    movedFrom(map, controlBefore)
                }
            }.isSuccess
            if (!controlMoved) controlBefore = awaitStableCameraTarget(map)
        }
        val controlMillis = SystemClock.uptimeMillis() - controlStart
        assertTrue("The control drag did not move the map", controlMoved)

        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) { historyIsShowing() }

        val popStartedAt = SystemClock.uptimeMillis()
        Espresso.pressBack()

        val mapCallbackRegistered = AtomicBoolean(false)
        val returnedMap = AtomicReference<MapLibreMap?>(null)
        val mapReadyAt = AtomicLong(-1L)
        val historyGoneAt = AtomicLong(-1L)
        composeRule.waitUntil(NAVIGATION_TIMEOUT_MILLIS) {
            val view = composeRule.runOnIdle { attachedMapView() }
            if (view != null && mapCallbackRegistered.compareAndSet(false, true)) {
                composeRule.runOnIdle {
                    view.getMapAsync { readyMap ->
                        returnedMap.set(readyMap)
                        mapReadyAt.compareAndSet(-1L, SystemClock.uptimeMillis())
                    }
                }
            }
            if (!historyIsShowing()) {
                historyGoneAt.compareAndSet(-1L, SystemClock.uptimeMillis())
            }
            mapReadyAt.get() >= 0L && historyGoneAt.get() >= 0L
        }

        val readyMap = requireNotNull(returnedMap.get()) { "No map after returning" }
        val readyAfterHistoryMillis = (mapReadyAt.get() - historyGoneAt.get()).coerceAtLeast(0L)
        assertTrue(
            "The history transition ended ${readyAfterHistoryMillis}ms before the map became ready",
            readyAfterHistoryMillis <= MAP_READY_AFTER_HISTORY_BUDGET_MILLIS,
        )

        val before = awaitStableCameraTarget(readyMap)
        // The cover is deliberately still up here: fog has not been rebuilt for this viewport yet,
        // and hiding it early is what P4-008 forbids. What must not happen is the cover eating the
        // first gesture, so exactly one drag is sent after the history transition is gone.
        val coverUp = composeRule.onAllNodesWithTag(MapSurfaceTestTags.FogSafetyCover)
            .fetchSemanticsNodes()
            .isNotEmpty()
        val start = SystemClock.uptimeMillis()
        drag()
        val firstDragMoved = runCatching {
            composeRule.waitUntil(DRAG_RESULT_TIMEOUT_MILLIS) {
                movedFrom(readyMap, before)
            }
        }.isSuccess
        val firstDragMillis = SystemClock.uptimeMillis() - start
        val beforeSecondDrag = awaitStableCameraTarget(readyMap)
        val secondStart = SystemClock.uptimeMillis()
        drag()
        val secondDragMoved = runCatching {
            composeRule.waitUntil(DRAG_RESULT_TIMEOUT_MILLIS) {
                movedFrom(readyMap, beforeSecondDrag)
            }
        }.isSuccess
        val secondDragMillis = SystemClock.uptimeMillis() - secondStart
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString(
                    "stream",
                        "TrailVeil return-from-history interactivity: firstDragMoved=$firstDragMoved " +
                        "firstDragMillis=$firstDragMillis secondDragMoved=$secondDragMoved " +
                        "secondDragMillis=$secondDragMillis controlMillis=$controlMillis " +
                        "controlAttempts=$controlAttempts " +
                        "popMillis=${historyGoneAt.get() - popStartedAt} " +
                        "mapReadyAfterHistoryMillis=$readyAfterHistoryMillis " +
                        "coverStillUp=$coverUp zoom=${readyMap.cameraPosition.zoom}\n",
                )
            },
        )
        assertTrue(
            "The first drag after the history transition did not move the map; " +
                "control moved in ${controlMillis}ms",
            firstDragMoved,
        )
        assertTrue(
            "The second drag on the returned main-map instance did not move the map",
            secondDragMoved,
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

    private fun movedFrom(map: MapLibreMap, before: LatLng): Boolean {
        val now = cameraTarget(map)
        return Math.abs(now.latitude - before.latitude) > MOVEMENT_TOLERANCE_DEGREES ||
            Math.abs(now.longitude - before.longitude) > MOVEMENT_TOLERANCE_DEGREES
    }

    private fun cameraTarget(map: MapLibreMap): LatLng =
        composeRule.runOnIdle {
            requireNotNull(map.cameraPosition.target) { "Map camera has no target" }
        }

    private fun awaitStableCameraTarget(map: MapLibreMap): LatLng {
        var previous: LatLng? = null
        var latest: LatLng? = null
        var stablePolls = 0
        composeRule.waitUntil(CAMERA_STABILITY_TIMEOUT_MILLIS) {
            val current = cameraTarget(map)
            latest = current
            val last = previous
            stablePolls = if (
                last != null &&
                Math.abs(current.latitude - last.latitude) <= MOVEMENT_TOLERANCE_DEGREES &&
                Math.abs(current.longitude - last.longitude) <= MOVEMENT_TOLERANCE_DEGREES
            ) {
                stablePolls + 1
            } else {
                0
            }
            previous = current
            stablePolls >= CAMERA_STABLE_POLL_COUNT
        }
        return requireNotNull(latest) { "Map camera never produced a stable target" }
    }

    private fun drag() {
        val view = requireNotNull(composeRule.runOnIdle { attachedMapView() }) {
            "No attached MapView for drag"
        }
        val location = IntArray(2)
        composeRule.runOnIdle { view.getLocationOnScreen(location) }
        // The initial camera may be at MapLibre's device-specific minimum zoom, where the world
        // height is constrained to the viewport and a vertical drag can be correctly clamped.
        // Longitude wraps, so a horizontal drag remains a valid input-acceptance signal there.
        val fromX = (location[0] + view.width * 2 / 3).toFloat()
        val toX = (location[0] + view.width / 3).toFloat()
        val y = (location[1] + view.height / 2).toFloat()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, y, 0),
        )
        repeat(DRAG_STEPS) { step ->
            SystemClock.sleep(DRAG_STEP_MILLIS)
            val x = fromX + (toX - fromX) * (step + 1) / DRAG_STEPS
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
                toX,
                y,
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

        /**
         * Engineering headroom after the visible 250 ms navigation transition, not a universal
         * human-perception threshold. The accepted transition is meant to cover MapView startup;
         * a longer hidden tail would recreate the user's original ambiguity.
         */
        const val MAP_READY_AFTER_HISTORY_BUDGET_MILLIS = 250L
        const val DRAG_RESULT_TIMEOUT_MILLIS = 1_000L
        const val CONTROL_DRAG_RESULT_TIMEOUT_MILLIS = 500L
        const val CAMERA_STABILITY_TIMEOUT_MILLIS = 2_000L
        const val MAP_READY_TIMEOUT_SECONDS = 20L
        const val MOVEMENT_TOLERANCE_DEGREES = 1e-6
        const val DRAG_STEPS = 6
        const val DRAG_STEP_MILLIS = 16L
        const val CONTROL_ATTEMPT_LIMIT = 10
        const val CAMERA_STABLE_POLL_COUNT = 2
        const val DISCLOSURE_POLLS = 40
        const val POLL_MILLIS = 100L
    }
}
