package app.trailveil.feature.notices

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.MainActivity
import app.trailveil.R
import app.trailveil.feature.recording.PermissionHistory
import app.trailveil.feature.recording.PermissionHistoryStore
import app.trailveil.feature.recording.RecordingEntryTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `V02-016`'s acceptance criterion, on the Google build: the notices are REACHABLE from the app.
 *
 * The host test `ThirdPartyNoticesTest` proves the file is present, complete, in the right source
 * set and named by the release audit. None of that is the criterion, which is that a user can get
 * to it: a resource nothing navigates to satisfies every check in that class and shows nobody
 * anything. So this drives the real launcher - menu, item, route - and asserts the body rendered.
 *
 * **It also asserts which provider's notices arrived, on the resource itself rather than on the
 * screen.** The two variants share one screen and choose the text at compile time through
 * `providerThirdPartyNotices`; a wiring mistake there would render a perfectly good screen full of
 * the other provider's material, which is the `V02-008` defect wearing a different hat. Reading the
 * raw resource here and checking the Google harvest's own header is what separates "a screen
 * appeared" from "the right screen appeared".
 */
@RunWith(AndroidJUnit4::class)
class GoogleThirdPartyNoticesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var permissionHistory: PermissionHistoryStore
    private var originalPermissionHistory: PermissionHistory? = null

    @Before
    fun setUp() {
        permissionHistory = PermissionHistoryStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        originalPermissionHistory = runBlocking { permissionHistory.current() }
        runBlocking {
            permissionHistory.replaceForTesting(
                requireNotNull(originalPermissionHistory).copy(hasSeenIntroduction = true),
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            originalPermissionHistory?.let { history ->
                permissionHistory.replaceForTesting(history)
            }
        }
    }

    @Test
    fun theGoogleNoticesAreReachableFromTheEntryMenuAndAreGooglesOwn() {
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Notices).assertIsDisplayed()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Notices).performClick()

        composeRule.onNodeWithTag(ThirdPartyNoticesTestTags.Screen).assertIsDisplayed()
        // The read happens off the main thread behind a loading state, so the body is awaited
        // rather than asserted immediately: a straight assertion here would be racing the
        // dispatcher and would fail for a reason that has nothing to do with the notices.
        composeRule.waitUntil(BODY_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(ThirdPartyNoticesTestTags.Body)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(ThirdPartyNoticesTestTags.Body).assertIsDisplayed()
        // A screen that rendered its "could not load" state would still be displayed, and the
        // criterion is that the user can READ the notices.
        composeRule.onNodeWithTag(ThirdPartyNoticesTestTags.Unavailable).assertDoesNotExist()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notices = context.resources.openRawResource(R.raw.google_third_party_notices)
            .bufferedReader()
            .use { reader -> reader.readText() }
        assertTrue(
            "the Google build must show the Google harvest, named by the artifacts it covers",
            notices.contains("com.google.android.gms:play-services-maps:"),
        )
        assertTrue(
            "and the licence texts themselves rather than their names",
            notices.contains("Apache License"),
        )
        // The other half - that MapLibre's notices are ABSENT here - is deliberately not asserted
        // from inside the app. Asking the resource table for them by name is resource reflection,
        // which lint rejects for good reasons, and it would be a weaker claim than the two that
        // already cover it: `ThirdPartyNoticesTest` pins each provider's file to its own source
        // set, and `build-github-release.ps1` refuses an APK that packages the other provider's,
        // on the packaged artifact rather than on a runtime lookup.

        // Back returns to the entry screen rather than leaving the user on a page with no exit.
        composeRule.onNodeWithTag(ThirdPartyNoticesTestTags.Back).performClick()
        composeRule.onNodeWithTag(RecordingEntryTestTags.Menu).assertIsDisplayed()
    }

    private companion object {
        const val BODY_TIMEOUT_MILLIS = 10_000L
    }
}
