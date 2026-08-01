package app.trailveil

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.feature.recording.LocationNotice
import app.trailveil.feature.recording.NotificationNotice
import app.trailveil.feature.recording.RecordingEntryScreen
import app.trailveil.feature.recording.RecordingEntryTestTags
import app.trailveil.feature.recording.RecordingEntryUiState
import app.trailveil.feature.recording.RecordingDisplayState
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

        composeRule.onNodeWithTag(RecordingEntryTestTags.Start).assertIsDisplayed()
        assertEquals(0, startCalls.get())
        assertEquals(0, locationCalls.get())
        assertEquals(0, notificationCalls.get())
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

        composeRule.onNodeWithTag(RecordingEntryTestTags.Start).performClick()

        assertEquals(1, startCalls.get())
    }

    @Test
    fun approximateLocationShowsPreciseAction() {
        val actions = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(
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
    }

    @Test
    fun mapRecenterAndHistoryControlsAreExplicitUserActions() {
        val recenterCalls = AtomicInteger()
        val historyCalls = AtomicInteger()
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(canRecenter = true),
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
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.History)
            .performScrollTo()
            .performClick()
        assertEquals(1, recenterCalls.get())
        assertEquals(1, historyCalls.get())
    }

    @Test
    fun mapRecenterIsDisabledUntilAPersistedPointExists() {
        composeRule.setContent {
            RecordingEntryScreen(
                state = RecordingEntryUiState(canRecenter = false),
                onStart = {},
                onStop = {},
                onLocationAction = {},
                onDismissLocationNotice = {},
                onNotificationAction = {},
            )
        }

        composeRule.onNodeWithTag(RecordingEntryTestTags.Recenter).assertIsNotEnabled()
    }
}
