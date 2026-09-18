package app.trailveil.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trailveil.R
import app.trailveil.harness.DemoExplorationSection

internal object HarnessSettingsTestTags {
    const val Section = "harness_settings_fog_arm"
    const val SelectedScheme = "harness_settings_selected_fog_scheme"
}

/** Final scheme is fixed at startup; demo-data controls remain independent of fog selection. */
@Composable
internal fun HarnessSettingsSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(HarnessSettingsTestTags.Section),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DemoExplorationSection()
        Text(
            text = stringResource(R.string.settings_fog_arm_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.settings_fog_selected_scheme),
            modifier = Modifier.testTag(HarnessSettingsTestTags.SelectedScheme),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
