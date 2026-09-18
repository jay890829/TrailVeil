package app.trailveil.feature.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.trailveil.TrailVeilApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import app.trailveil.MainActivity
import app.trailveil.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Tests fresh, retired, unknown and wrong-type preferences without touching seed/clear controls. */
internal fun verifyFixedFogScheme(
    rule: ComposeTestRule,
    retiredIds: List<String>,
    expectedLabel: String,
    initialize: () -> String,
    activeLabel: () -> String,
) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val preferences = context.getSharedPreferences("trailveil-fog-arm", Context.MODE_PRIVATE)
    val previous = preferences.all["arm"]
    try {
        // Exercise the actual startup seam for every old stored id, including a corrupt type.
        val args = InstrumentationRegistry.getArguments()
        val coldLaunch = args.getString("zColdLaunch") == "true"
        if (coldLaunch) {
            val expected = args.getString("zExpectedLegacyArm")
            if (expected == "absent") assertEquals(null, previous)
            else if (expected != null) assertEquals(expected, previous)
        }
        for (value in if (coldLaunch) emptyList() else listOf<Any?>(null) + retiredIds + listOf(7)) {
            @SuppressLint("UseKtx") val edit = preferences.edit()
            when (value) {
                null -> edit.remove("arm")
                is Int -> edit.putInt("arm", value)
                else -> edit.putString("arm", value as String)
            }
            assertTrue(edit.commit())
            assertEquals(expectedLabel, initialize())
            assertEquals(expectedLabel, activeLabel())
        }
        if (coldLaunch) reportCanonicalCounts("before-cold-ui")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun showSettings() {
                rule.waitUntil(30_000) {
                    rule.onAllNodes(hasTestTag("settings")).fetchSemanticsNodes().isNotEmpty() ||
                        rule.onAllNodes(hasTestTag("recording_entry_menu")).fetchSemanticsNodes().isNotEmpty()
                }
                if (rule.onAllNodes(hasTestTag("settings")).fetchSemanticsNodes().isEmpty()) {
                    rule.onAllNodes(hasTestTag("harness_active_fog_arm")).assertCountEquals(0)
                    rule.onAllNodes(hasTestTag("harness_fog_arm_badge_bounds")).assertCountEquals(0)
                    assertEquals(expectedLabel, activeLabel())
                    rule.onNodeWithTag("recording_entry_menu").performClick()
                    rule.onNodeWithTag("recording_entry_settings").performClick()
                }
                rule.onNodeWithTag("harness_settings_selected_fog_scheme").performScrollTo()
                    .assertTextEquals(context.getString(R.string.settings_fog_selected_scheme))
                rule.onAllNodes(SemanticsMatcher("retired fog option or restart") { node ->
                    val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                    tag?.startsWith("harness_fog_arm_option_") == true || tag == "harness_settings_restart"
                }).assertCountEquals(0)
            }
            showSettings()
            scenario.recreate()
            showSettings()
            assertEquals(expectedLabel, activeLabel())
            rule.onNodeWithTag("settings_back").performScrollTo().performClick()
            rule.waitUntil(30_000) {
                rule.onAllNodes(hasTestTag("recording_entry_menu")).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onAllNodes(hasTestTag("harness_active_fog_arm")).assertCountEquals(0)
            rule.onAllNodes(hasTestTag("harness_fog_arm_badge_bounds")).assertCountEquals(0)
            assertEquals(expectedLabel, activeLabel())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "Z_FIXED_SCHEME cold=$coldLaunch active=${activeLabel()} realNavigation=true noSelector=true noDiagnosticBadge=true\n")
            })
        }
        if (coldLaunch) reportCanonicalCounts("after-cold-ui")
    } finally {
        @SuppressLint("UseKtx") val edit = preferences.edit()
        when (previous) {
            null -> edit.remove("arm")
            is String -> edit.putString("arm", previous)
            is Int -> edit.putInt("arm", previous)
            is Boolean -> edit.putBoolean("arm", previous)
            is Long -> edit.putLong("arm", previous)
            is Float -> edit.putFloat("arm", previous)
            else -> error("Unexpected fixture preference type")
        }
        assertTrue(edit.commit())
    }
}

/** Setup-only mode, run on the sealed old APK before install -r; not a verification PASS. */
internal fun stageLegacyFogPreference(save: (String) -> Unit, readId: () -> String): Boolean {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val stage = InstrumentationRegistry.getArguments().getString("zStageLegacy") ?: return false
    val context = instrumentation.targetContext
    val preferences = context.getSharedPreferences("trailveil-fog-arm", Context.MODE_PRIVATE)
    if (stage == "absent") {
        @SuppressLint("UseKtx") val edit = preferences.edit().remove("arm")
        assertTrue(edit.commit())
        assertEquals(null, preferences.all["arm"])
    } else {
        save(stage)
        assertEquals(stage, preferences.getString("arm", null))
        assertEquals("setup must run on the old selectable APK", stage, readId())
    }
    reportCanonicalCounts("staged-$stage")
    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Z_UPGRADE_SETUP staged=$stage\n") })
    return true
}

private fun reportCanonicalCounts(phase: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val application = instrumentation.targetContext.applicationContext as TrailVeilApplication
    val counts = runBlocking(Dispatchers.IO) {
        val db = application.appContainer.databaseForTesting().openHelper.readableDatabase
        db.query("SELECT (SELECT COUNT(*) FROM recording_sessions), COUNT(*), COALESCE(MAX(id),0) FROM track_points").use {
            check(it.moveToFirst()); "${it.getLong(0)},${it.getLong(1)},${it.getLong(2)}"
        }
    }
    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Z_CANONICAL_COUNTS phase=$phase counts=$counts\n") })
}
