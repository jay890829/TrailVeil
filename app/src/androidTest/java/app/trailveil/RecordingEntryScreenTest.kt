package app.trailveil

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.feature.recording.LocationNotice
import app.trailveil.feature.recording.NotificationNotice
import app.trailveil.feature.recording.RecordingEntryScreen
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.feature.recording.RecordingEntryUiState
import app.trailveil.feature.recording.RecordingDisplayState
import app.trailveil.feature.recording.RecordingStartNotice
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
    fun activeRecordingHasAnInAppStopWhenNotificationIsHidden() {
        val stopCalls = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    recordingActive = true,
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
                    recordingActive = true,
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
        val startNotice = mutableStateOf<RecordingStartNotice?>(RecordingStartNotice.STARTED)
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    startNotice = startNotice.value,
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
                    recordingActive = true,
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
    fun aDismissedTerminalStateStaysHiddenUntilTheStateChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val completed = context.getString(R.string.recording_state_completed)
        val interrupted = context.getString(R.string.recording_state_interrupted)
        val displayState = mutableStateOf(RecordingDisplayState.COMPLETED)
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
                    firstVisit = false,
                    recordingState = displayState.value,
                ),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithText(completed).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingStateDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertDoesNotExist()
        composeRule.onNodeWithText(completed).assertDoesNotExist()

        // A different terminal outcome is new information and must not inherit the dismissal.
        composeRule.runOnIdle { displayState.value = RecordingDisplayState.INTERRUPTED }
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertIsDisplayed()
        composeRule.onNodeWithText(interrupted).assertIsDisplayed()

        // Dismissing again must not permanently silence a state the user already dismissed once.
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingStateDismiss).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.RecordingState).assertDoesNotExist()
        composeRule.runOnIdle { displayState.value = RecordingDisplayState.COMPLETED }
        composeRule.onNodeWithText(completed).assertIsDisplayed()
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
