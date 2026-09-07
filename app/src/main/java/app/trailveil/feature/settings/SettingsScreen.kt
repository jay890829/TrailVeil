package app.trailveil.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trailveil.R

internal object SettingsTestTags {
    const val Screen = "settings"
    const val Back = "settings_back"
    const val Notices = "settings_notices"
}

/**
 * The app's settings.
 *
 * Introduced by `V03-013` because the fog-arm selector needed somewhere to live and a second
 * launcher icon was the wrong answer - `V02-005`'s launcher inversion deliberately leaves the
 * `googlePoc` manifest with no launcher of its own, and that invariant is worth more than the
 * convenience of a separate icon. A settings destination is where the selector belongs anyway,
 * and it is the place later product settings go rather than each one inventing its own route.
 *
 * The harness section is a per-build-type seam. In a published build it renders nothing and cannot
 * name what it would have rendered, so this screen shows only product rows there.
 */
@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onOpenNotices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag(SettingsTestTags.Screen),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.testTag(SettingsTestTags.Back),
            ) {
                Text(stringResource(R.string.settings_back))
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            HarnessSettingsSection()

            Text(
                text = stringResource(R.string.settings_about_section),
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedButton(
                onClick = onOpenNotices,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsTestTags.Notices),
            ) {
                Text(stringResource(R.string.notices_menu))
            }
        }
    }
}
