package app.trailveil.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A distributed build has one fog design, so its settings have nothing to choose between.
 *
 * `V03-013`'s arm selectors are measurement fixtures. This build type compiles no arm enum, no
 * stored preference and no restart control. Declared once per build type rather than switched on a
 * flag, so exactly one twin is compiled into any variant and a distributed build cannot acquire
 * the other by accident.
 *
 * **This provider needed the split that the other one already had.** `src/mapLibre` is compiled by
 * `debug`, `internal` AND `release`, so a selector placed there would ship; the harness therefore
 * lives in `src/debug` and these twins cover the two build types that leave the machine.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun HarnessSettingsSection(modifier: Modifier = Modifier) {
    // Intentionally empty; see above.
}
