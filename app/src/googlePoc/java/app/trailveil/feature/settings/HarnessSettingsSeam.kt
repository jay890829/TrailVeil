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
import app.trailveil.map.GoogleFogArm
import kotlin.system.exitProcess

internal object HarnessSettingsTestTags {
    const val Section = "harness_settings_fog_arm"
    const val Restart = "harness_settings_restart"
}

/**
 * `V03-013`: the harness twin, which lets a person choose the fog arm this build runs.
 *
 * **Storing an arm is not the same as running it, and the UI says so rather than pretending
 * otherwise.** [app.trailveil.map.GoogleFogCoverageArm]'s contract is that a binding reads the
 * profile once in its field initialisers, so a map that is already composed keeps the arm it was
 * built with. Selecting here writes the preference and applies it to the process, but the honest
 * guarantee is only available after a restart - which is why the restart control sits next to the
 * choice instead of being left implicit.
 *
 * Every other build type declares its own twin of this function; all of them draw nothing and
 * cannot name [GoogleFogArm] at all.
 */
@Composable
internal fun HarnessSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember(context) { mutableStateOf(GoogleFogArm.stored(context)) }

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
                R.string.fog_arm_current,
                selected.label,
                selected.paddingTiles.toString(),
                selected.mosaicOverlay.toString(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        GoogleFogArm.entries.forEach { arm ->
            val chosen = arm == selected
            OutlinedButton(
                onClick = {
                    GoogleFogArm.store(context, arm)
                    arm.apply()
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
