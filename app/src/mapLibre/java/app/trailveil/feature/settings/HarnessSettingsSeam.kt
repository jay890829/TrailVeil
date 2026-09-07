package app.trailveil.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The MapLibre variant's twin, which currently has no arms to choose between.
 *
 * `V03-013` lists two MapLibre experiments - a wider anchored mosaic and `CustomGeometrySource`
 * vector fog - but neither exists yet: unlike the Google side, `src/mapLibre` has no arm seam of
 * any kind, so there is nothing here to select. This file exists so `src/main`'s settings screen
 * can call one symbol on every build type; when the MapLibre arms are built, this is where their
 * selector goes and the two published twins stay empty.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun HarnessSettingsSection(modifier: Modifier = Modifier) {
    // Intentionally empty; see above.
}
