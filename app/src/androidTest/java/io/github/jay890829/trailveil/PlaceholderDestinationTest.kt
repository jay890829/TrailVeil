package io.github.jay890829.trailveil

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceholderDestinationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun placeholderDestinationIsDisplayed() {
        val placeholderText = composeRule.activity.getString(R.string.placeholder_destination)

        composeRule.onNodeWithText(placeholderText).assertIsDisplayed()
    }
}
