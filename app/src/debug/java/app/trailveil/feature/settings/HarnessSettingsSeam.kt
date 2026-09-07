package app.trailveil.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trailveil.R
import app.trailveil.map.MapLibreFogArm
import kotlin.system.exitProcess

internal object HarnessSettingsTestTags {
    const val Section = "harness_settings_fog_arm"
    const val Restart = "harness_settings_restart"
}

/**
 * `V03-013`: this provider's mosaic-size arm.
 *
 * **A restart is not optional here, and the UI says so.** The other provider's selector is process
 * state a binding reads; this one is a constructor argument to the fog runtime, which is built
 * once per process. Selecting an arm writes the preference and nothing else - the running runtime
 * keeps the mosaic it was constructed with until the process goes away.
 *
 * Every other build type declares its own twin of this function and draws nothing.
 */
@Composable
internal fun HarnessSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember(context) { mutableStateOf(MapLibreFogArm.stored(context)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(HarnessSettingsTestTags.Section),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_fog_arm_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(
                R.string.settings_fog_arm_current,
                selected.label,
                selected.mosaicPaddingTiles.toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        MapLibreFogArm.entries.forEach { arm ->
            val chosen = arm == selected
            OutlinedButton(
                onClick = {
                    MapLibreFogArm.store(context, arm)
                    selected = arm
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (chosen) "✓ ${arm.label}" else arm.label)
            }
        }
        Text(
            text = stringResource(R.string.settings_fog_arm_restart_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { exitProcess(0) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HarnessSettingsTestTags.Restart),
        ) {
            Text(stringResource(R.string.settings_fog_arm_restart))
        }
    }
}
