package app.trailveil

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.trailveil.feature.recording.TRANSIENT_NOTICE_WINDOW_MILLIS
import app.trailveil.feature.recording.LocationNotice
import app.trailveil.feature.recording.NotificationNotice
import app.trailveil.feature.recording.RecordingEntryScreen
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.feature.recording.RecordingEntryUiState
import app.trailveil.feature.recording.RecordingDisplayState
import app.trailveil.feature.recording.RecordingStartNotice
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.widgets.CompassView

@RunWith(AndroidJUnit4::class)
class RecordingEntryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compositionDoesNotRequestPermissionOrStartRecording() {
        val startCalls = AtomicInteger()
        val locationCalls = AtomicInteger()
        val notificationCalls = AtomicInteger()

        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(),
                onStart = startCalls::incrementAndGet,
                onStop = {},
                onLocationAction = locationCalls::incrementAndGet,
                onDismissLocationNotice = {},
                onNotificationAction = notificationCalls::incrementAndGet,
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacySheet).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacyDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Start).assertIsDisplayed()
        assertEquals(0, startCalls.get())
        assertEquals(0, locationCalls.get())
        assertEquals(0, notificationCalls.get())
    }

    @Test
    fun firstVisitPresentsThePrivacyExplanationBeforeStartIsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(firstVisit = true),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacySheet).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.recording_entry_privacy_title_first))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.recording_entry_privacy_body))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.recording_entry_privacy_retention_body))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.recording_entry_permissions_summary))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.recording_entry_consent_note))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Start).assertDoesNotExist()

        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacyDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacySheet).assertDoesNotExist()
    }

    @Test
    fun privacyExplanationStaysReachableFromTheMenuWithoutOccupyingTheMap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(firstVisit = false),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacySheet).assertDoesNotExist()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Privacy).assertDoesNotExist()
        composeRule
            .onNodeWithText(context.getString(R.string.recording_entry_privacy_body))
            .assertDoesNotExist()

        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Privacy)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacySheet).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.recording_entry_privacy_body))
            .assertIsDisplayed()

        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacyDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacySheet).assertDoesNotExist()
        composeRule
            .onNodeWithText(context.getString(R.string.recording_entry_privacy_body))
            .assertDoesNotExist()
    }

    @Test
    fun startIsTheOnlyPrimaryEntryEffect() {
        val startCalls = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(firstVisit = false),
                onStart = startCalls::incrementAndGet,
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Start).performClick()

        assertEquals(1, startCalls.get())
    }

    @Test
    fun approximateLocationShowsPreciseAction() {
        val actions = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    locationNotice = LocationNotice.PRECISE_RATIONALE,
                ),
                onStart = {},
                onStop = {},
                onLocationAction = actions::incrementAndGet,
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        val preciseLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.permission_request_precise)
        composeRule.onNodeWithTag(RecordingEntryTestTags.LocationNotice).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.LocationAction)
            .assertTextEquals(preciseLabel)
            .performClick()
        assertEquals(1, actions.get())
    }

    @Test
    fun backgroundStartGuidanceOffersSettingsAndCanBeDismissed() {
        // Holds the screen and not the route - it feeds itself the flag, which is exactly the
        // escape hatch verifiers caught twice in this task family. It is worth having only because
        // the three AbandonedRecordingStateTest assertions bind the route's raising and clearing.
        val actions = AtomicInteger()
        val dismissals = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    backgroundStartNotice = true,
                ),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
                onBackgroundStartAction = actions::incrementAndGet,
                onDismissBackgroundStartNotice = dismissals::incrementAndGet,
            )
        }

        val settingsLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.permission_open_app_settings)
        composeRule.onNodeWithTag(RecordingEntryTestTags.BackgroundStartNotice).assertIsDisplayed()
        // Composition alone fires nothing - the guarantee the other cards already make.
        assertEquals(0, actions.get())
        assertEquals(0, dismissals.get())
        composeRule.onNodeWithTag(RecordingEntryTestTags.BackgroundStartAction)
            .assertTextEquals(settingsLabel)
            .performClick()
        assertEquals(1, actions.get())
        assertEquals(0, dismissals.get())
        composeRule.onNodeWithTag(RecordingEntryTestTags.BackgroundStartDismiss).performClick()
        assertEquals(1, dismissals.get())
    }

    @Test
    fun theBackgroundStartCardIsNotRenderedUnlessEarned() {
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(firstVisit = false),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.BackgroundStartNotice).assertDoesNotExist()
    }

    @Test
    fun notificationDenialIsInformationalAndStartRemainsEnabled() {
        val startCalls = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    notificationNotice = NotificationNotice.SETTINGS,
                ),
                onStart = startCalls::incrementAndGet,
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.NotificationNotice)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Start)
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, startCalls.get())
    }

    @Test
    fun anAbandonedExplorationOffersBothContinuingAndEndingIt() {
        // The route decides both flags, and a verifier showed the screen could quietly drop one:
        // reinstating the old exclusive `else if` branch - which strands a user who can neither
        // resume nor end an ACTIVE row whose runtime is gone - left every other test green.
        val startCalls = AtomicInteger()
        val stopCalls = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    stopOffered = true,
                    startOffered = true,
                    recordingState = RecordingDisplayState.ABANDONED,
                ),
                onStart = startCalls::incrementAndGet,
                onStop = stopCalls::incrementAndGet,
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Stop).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Start).assertIsDisplayed()
    }

    @Test
    fun activeRecordingHasAnInAppStopWhenNotificationIsHidden() {
        val stopCalls = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    stopOffered = true,
                    startOffered = false,
                    notificationNotice = NotificationNotice.SETTINGS,
                ),
                onStart = {},
                onStop = stopCalls::incrementAndGet,
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Stop)
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, stopCalls.get())
    }

    @Test
    fun poorSignalAndStoppingStatesAreExplicit() {
        val poorSignal = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.recording_state_poor_signal)
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    stopOffered = true,
                    startOffered = false,
                    recordingState = RecordingDisplayState.POOR_SIGNAL,
                ),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertIsDisplayed()
        composeRule.onNodeWithText(poorSignal).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingStateDismiss)
            .assertDoesNotExist()
    }

    @Test
    fun aDismissedStartNoticeStaysHiddenUntilTheNoticeChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val started = context.getString(R.string.recording_started)
        val stopRequested = context.getString(R.string.recording_stop_requested)
        val raisedAt = System.currentTimeMillis()
        val startNotice = mutableStateOf<RecordingStartNotice?>(RecordingStartNotice.STARTED)
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    startNotice = startNotice.value,
                    startNoticeRaisedAt = raisedAt,
                ),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.StartNotice).assertIsDisplayed()
        composeRule.onNodeWithText(started).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.StartNoticeDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.StartNotice).assertDoesNotExist()
        composeRule.onNodeWithText(started).assertDoesNotExist()

        // A different acknowledgement is new information and must not inherit the dismissal.
        composeRule.runOnIdle { startNotice.value = RecordingStartNotice.STOP_REQUESTED }
        composeRule.onNodeWithTag(RecordingEntryTestTags.StartNotice).assertIsDisplayed()
        composeRule.onNodeWithText(stopRequested).assertIsDisplayed()

        // Dismissing again must not permanently silence a notice the user dismissed once.
        composeRule.onNodeWithTag(RecordingEntryTestTags.StartNoticeDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.StartNotice).assertDoesNotExist()
        composeRule.runOnIdle { startNotice.value = RecordingStartNotice.STARTED }
        composeRule.onNodeWithText(started).assertIsDisplayed()
    }

    @Test
    fun transientRecordingStatesCannotBeDismissed() {
        val displayState = mutableStateOf(RecordingDisplayState.STARTING)
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    stopOffered = true,
                    startOffered = false,
                    recordingState = displayState.value,
                ),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        listOf(
            RecordingDisplayState.STARTING,
            RecordingDisplayState.RECORDING,
            RecordingDisplayState.STOPPING,
        ).forEach { transient ->
            composeRule.runOnIdle { displayState.value = transient }
            composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertIsDisplayed()
            composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingStateDismiss)
                .assertDoesNotExist()
        }
    }

    @Test
    fun aDismissedOutcomeSurvivesTheScreenBeingRebuilt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val completed = context.getString(R.string.recording_state_completed)
        val interrupted = context.getString(R.string.recording_state_interrupted)
        val historyDestination = "History test destination"
        val endedAt = System.currentTimeMillis()
        val published = mutableStateOf(
            RecordingEntryUiState(
                firstVisit = false,
                recordingState = RecordingDisplayState.COMPLETED,
                latestSessionId = 7L,
                latestEndedAt = endedAt,
            ),
        )
        val activeNavController = AtomicReference<NavHostController>()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val navController = rememberNavController()
            SideEffect { activeNavController.set(navController) }
            NavHost(navController = navController, startDestination = "recording") {
                composable("recording") {
                    RecordingEntryScreen(
                        state = published.value,
                        onStart = {},
                        onStop = {},
                        onLocationAction = {},
                        onDismissLocationNotice = {},
                        onNotificationAction = {},
                        onOpenHistory = { navController.navigate("history") },
                        clockMillis = { endedAt },
                    )
                }
                composable("history") { Text(historyDestination) }
            }
        }

        composeRule.onNodeWithText(completed).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingStateDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertDoesNotExist()
        composeRule.onNodeWithText(completed).assertDoesNotExist()

        // Use a real Navigation destination round trip. Navigation Compose owns the saveable-state
        // holder that production relies on, so replacing this with a loading-frame toggle would
        // miss a destination whose state is discarded while it is off screen.
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History).performClick()
        composeRule.onNodeWithText(historyDestination).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(activeNavController.get().popBackStack()) }
        composeRule.onNodeWithText(completed).assertDoesNotExist()

        // StateRestorationTester exercises the SaveableStateRegistry contract used for both
        // configuration recreation and system process restoration of a retained task.
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText(completed).assertDoesNotExist()

        // Returning and restoration can each republish an unread/loading frame before the same
        // session arrives again. Neither frame is a new outcome, so neither may revive dismissal.
        composeRule.runOnIdle {
            published.value = RecordingEntryUiState(firstVisit = false, loading = true)
        }
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertDoesNotExist()
        composeRule.runOnIdle {
            published.value = RecordingEntryUiState(
                firstVisit = false,
                recordingState = RecordingDisplayState.COMPLETED,
                latestSessionId = 7L,
                latestEndedAt = endedAt,
            )
        }
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertDoesNotExist()
        composeRule.onNodeWithText(completed).assertDoesNotExist()

        // A different exploration's outcome is new information and must not inherit the dismissal.
        composeRule.runOnIdle {
            published.value = RecordingEntryUiState(
                firstVisit = false,
                recordingState = RecordingDisplayState.INTERRUPTED,
                latestSessionId = 8L,
                latestEndedAt = endedAt,
            )
        }
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertIsDisplayed()
        composeRule.onNodeWithText(interrupted).assertIsDisplayed()

        // Dismissing again must not permanently silence outcomes the user dismissed once before.
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingStateDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertDoesNotExist()
        composeRule.runOnIdle {
            published.value = RecordingEntryUiState(
                firstVisit = false,
                recordingState = RecordingDisplayState.COMPLETED,
                latestSessionId = 9L,
                latestEndedAt = endedAt,
            )
        }
        composeRule.onNodeWithText(completed).assertIsDisplayed()
    }

    @Test
    fun aCompletionStopsAnnouncingItselfOnceItsWindowHasPassed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val completed = context.getString(R.string.recording_state_completed)
        val interrupted = context.getString(R.string.recording_state_interrupted)
        val longAgo = System.currentTimeMillis() - TRANSIENT_NOTICE_WINDOW_MILLIS * 10L
        val published = mutableStateOf(
            RecordingEntryUiState(
                firstVisit = false,
                recordingState = RecordingDisplayState.COMPLETED,
                latestSessionId = 7L,
                latestEndedAt = longAgo,
            ),
        )
        composeRule.setContent {
            RecordingEntryScreen(
                state = published.value,
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertDoesNotExist()
        composeRule.onNodeWithText(completed).assertDoesNotExist()

        // A failed exploration is not a courtesy message, so it keeps waiting to be read.
        composeRule.runOnIdle {
            published.value = RecordingEntryUiState(
                firstVisit = false,
                recordingState = RecordingDisplayState.INTERRUPTED,
                latestSessionId = 7L,
                latestEndedAt = longAgo,
            )
        }
        composeRule.onNodeWithText(interrupted).assertIsDisplayed()
    }

    @Test
    fun anAcknowledgementOfAUserActionDoesNotLingerOrRestart() {
        // Driven entirely from the screen's injectable clock. The previous version anchored the
        // notice on System.currentTimeMillis() at state construction and then waited out the real
        // window - but the anchor was stamped before the first MapView composition, which takes
        // real seconds of its own, so a slow composition ate the window and the test raced the
        // wall clock. With the clock injected, composition time cannot touch the window and the
        // expiry is exercised by advancing time, not by spending it.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val started = context.getString(R.string.recording_started)
        val launchFailure = context.getString(R.string.recording_launch_failure)
        var fakeNow = 0L
        val published = mutableStateOf(
            RecordingEntryUiState(
                firstVisit = false,
                startNotice = RecordingStartNotice.STARTED,
                startNoticeRaisedAt = 0L,
            ),
        )
        composeRule.setContent {
            RecordingEntryScreen(
                state = published.value,
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
                clockMillis = { fakeNow },
            )
        }

        composeRule.onNodeWithText(started).assertIsDisplayed()
        // Advance past the window: the screen's own expiry delay fires on the test frame clock and
        // resamples the injected time.
        fakeNow = TRANSIENT_NOTICE_WINDOW_MILLIS + 1L
        composeRule.mainClock.advanceTimeBy(TRANSIENT_NOTICE_WINDOW_MILLIS + 100L)
        composeRule.waitUntil(TRANSIENT_NOTICE_WINDOW_MILLIS) {
            composeRule.onAllNodesWithText(started).fetchSemanticsNodes().isEmpty()
        }

        // Coming back to a stale acknowledgement must not buy it another window.
        composeRule.runOnIdle {
            published.value = RecordingEntryUiState(firstVisit = false, loading = true)
        }
        composeRule.runOnIdle {
            published.value = RecordingEntryUiState(
                firstVisit = false,
                startNotice = RecordingStartNotice.STARTED,
                startNoticeRaisedAt = 0L,
            )
        }
        composeRule.onNodeWithText(started).assertDoesNotExist()

        // A notice that reports a failure is not a courtesy, so it keeps waiting to be read.
        composeRule.runOnIdle {
            published.value = RecordingEntryUiState(
                firstVisit = false,
                startNotice = RecordingStartNotice.LAUNCH_FAILURE,
                startNoticeRaisedAt = 0L,
            )
        }
        composeRule.onNodeWithText(launchFailure).assertIsDisplayed()
    }

    @Test
    fun anUnloadedScreenPresentsNeitherTheDisclosureNorAnEnabledStart() {
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(loading = true, firstVisit = true),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        // `firstVisit` defaults to true before the stored history is read, so the disclosure must
        // wait for a real answer rather than flashing on the default.
        composeRule.onNodeWithTag(RecordingEntryTestTags.PrivacySheet).assertDoesNotExist()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Start).assertIsNotEnabled()
    }

    @Test
    fun mapRecenterAndHistoryControlsAreExplicitUserActions() {
        val recenterCalls = AtomicInteger()
        val historyCalls = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(firstVisit = false, canRecenter = true),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
                onRecenter = recenterCalls::incrementAndGet,
                onOpenHistory = historyCalls::incrementAndGet,
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History)
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, recenterCalls.get())
        assertEquals(1, historyCalls.get())
    }

    /**
     * Following is a mode the map stays in, not an action that happened once, so the button has to
     * keep saying which one it is — otherwise the only way to find out is to walk somewhere. The
     * press itself does the same thing in both states; only what it announces changes.
     */
    @Test
    fun theRecenterButtonSaysWhetherTheMapIsFollowing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val following = mutableStateOf(false)
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    canRecenter = true,
                    followingLocation = following.value,
                ),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
                onRecenter = { following.value = true },
            )
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.map_center_latest_location),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter).performClick()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.map_following_latest_location),
        ).assertIsDisplayed()
    }

    /**
     * The compass belongs to the map view, not to this screen's column of controls, so nothing
     * lays it out for us — it went wherever MapLibre put it, which was on top of the menu button.
     * Placing it takes arithmetic, and arithmetic is what silently drifts, so both halves of the
     * result are measured here: where it ended up, and that a notice cannot reach it.
     */
    @Test
    fun theCompassSitsBelowTheMenuAndNoNoticeReachesIt() {
        composeRule.setContent {
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

        composeRule.onNodeWithTag(RecordingEntryTestTags.LocationNotice).assertIsDisplayed()
        val compass = composeRule.waitForCompassBounds()
        val menu = composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).getUnclippedBoundsInRoot()
        val notice = composeRule.onNodeWithTag(RecordingEntryTestTags.LocationNotice)
            .getUnclippedBoundsInRoot()
        val density = composeRule.density

        with(density) {
            assertTrue(
                "The compass (top=${compass.top}px) is not below the menu button " +
                    "(bottom=${menu.bottom.roundToPx()}px)",
                compass.top >= menu.bottom.roundToPx(),
            )
            assertTrue(
                "A notice reaches ${notice.right.roundToPx()}px, past the compass's left edge " +
                    "at ${compass.left}px",
                notice.right.roundToPx() <= compass.left,
            )
        }
    }

    /**
     * The compass is only laid out once the map view has attached and MapLibre has applied the
     * margins the screen asked for, which happens after the first composition.
     */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForCompassBounds(): Rect {
        lateinit var bounds: Rect
        waitUntil(timeoutMillis = 15_000L) {
            val compass = runOnIdle { attachedCompassView() } ?: return@waitUntil false
            if (compass.width <= 0 || compass.height <= 0) return@waitUntil false
            val location = IntArray(2)
            compass.getLocationInWindow(location)
            bounds = Rect(
                location[0],
                location[1],
                location[0] + compass.width,
                location[1] + compass.height,
            )
            true
        }
        return bounds
    }

    private fun attachedCompassView(): View? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstNotNullOfOrNull { activity -> activity.window.decorView.findCompassView() }

    private fun View.findCompassView(): View? {
        if (this is CompassView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findCompassView()?.let { return it }
        }
        return null
    }

    @Test
    fun mapRecenterIsDisabledUntilAPersistedPointExists() {
        val recenterCalls = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(firstVisit = false, canRecenter = false),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
                onRecenter = recenterCalls::incrementAndGet,
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter).assertIsNotEnabled()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter).performClick()
        assertEquals(0, recenterCalls.get())
    }
}
