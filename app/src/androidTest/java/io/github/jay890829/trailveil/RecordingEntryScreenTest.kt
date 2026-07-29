package io.github.jay890829.trailveil

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jay890829.trailveil.feature.recording.LocationNotice
import io.github.jay890829.trailveil.feature.recording.NotificationNotice
import io.github.jay890829.trailveil.feature.recording.RecordingEntryScreen
import io.github.jay890829.trailveil.feature.recording.RecordingEntryTestTags
import io.github.jay890829.trailveil.feature.recording.RecordingEntryUiState
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
}
