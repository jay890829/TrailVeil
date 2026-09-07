package app.trailveil.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trailveil.R
import java.util.Locale
import kotlinx.coroutines.launch

internal object DemoExplorationTestTags {
    const val Section = "harness_settings_demo_data"
    const val Seed = "harness_settings_demo_seed"
    const val Clear = "harness_settings_demo_clear"
    const val Status = "harness_settings_demo_status"
}

/**
 * `V03-013`: the control that puts revealed ground on the map, shared by both harness build types.
 *
 * One implementation rather than one per provider, because the arms are compared ACROSS providers
 * as well as within one: two copies of this that drifted would seed two different walks, and the
 * difference would be read as a difference between the providers' fog.
 *
 * The section states what is currently seeded rather than what was last requested. A button that
 * says "seeded" while the database is empty is worse than no button, because the conclusion drawn
 * from it - "this arm renders nothing" - looks exactly like the fog defect the harness exists to
 * find.
 */
@Composable
internal fun DemoExplorationSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seeded by remember(context) { mutableIntStateOf(-1) }
    var anchorLabel by remember(context) { mutableStateOf<String?>(null) }
    var busy by remember(context) { mutableStateOf(false) }
    var reload by remember(context) { mutableIntStateOf(0) }

    LaunchedEffect(context, reload) {
        seeded = runCatching { DemoExplorationSeeder.seededPointCount(context) }.getOrDefault(0)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DemoExplorationTestTags.Section),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_demo_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = when {
                busy -> stringResource(R.string.settings_demo_working)
                seeded <= 0 -> stringResource(R.string.settings_demo_status_empty)
                else -> stringResource(
                    R.string.settings_demo_status,
                    seeded.toString(),
                    anchorLabel ?: stringResource(R.string.settings_demo_anchor_unknown),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(DemoExplorationTestTags.Status),
        )
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    val anchor = DemoExplorationSeeder.anchor(context)
                    val outcome = runCatching {
                        DemoExplorationSeeder.seed(context, anchor)
                    }.getOrNull()
                    anchorLabel = outcome?.let { result ->
                        String.format(
                            Locale.US,
                            "%.4f, %.4f",
                            result.anchor.latitude,
                            result.anchor.longitude,
                        )
                    }
                    busy = false
                    reload += 1
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DemoExplorationTestTags.Seed),
        ) {
            Text(stringResource(R.string.settings_demo_seed))
        }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    runCatching { DemoExplorationSeeder.clear(context) }
                    anchorLabel = null
                    busy = false
                    reload += 1
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DemoExplorationTestTags.Clear),
        ) {
            Text(stringResource(R.string.settings_demo_clear))
        }
        Text(
            text = stringResource(R.string.settings_demo_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
