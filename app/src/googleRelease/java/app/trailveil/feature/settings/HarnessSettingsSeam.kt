package app.trailveil.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A published build has one fog design, so its settings have nothing to choose between.
 *
 * `V03-013`'s arm selector is a measurement fixture, and like
 * [app.trailveil.map.googleFogOverlayInstaller]'s release twin this one **cannot name what it
 * would have rendered**: no arm enum, no stored preference, no restart control is compiled into
 * `googleRelease`. Declared once per build type rather than switched on a flag, so exactly one of
 * the twins is compiled into any variant and a published build cannot acquire the other by
 * accident.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun HarnessSettingsSection(modifier: Modifier = Modifier) {
    // Intentionally empty; see above.
}
