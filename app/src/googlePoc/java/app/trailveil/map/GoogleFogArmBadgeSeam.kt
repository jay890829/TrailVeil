package app.trailveil.map

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * `V03-013`: the harness twin, which names the arm this process is running.
 *
 * The badge is an acceptance criterion rather than a convenience. Five of the arms differ only in
 * two numbers a person cannot see, and section 15k's defect is the standing reminder of what that
 * costs: a prototype whose every visible metric improved while it exposed 89.344% of unexplored
 * ground. A trial that cannot be attributed to an arm is not evidence, so the arm is on screen.
 *
 * It deliberately does NOT reuse [MapStatusBadge]: that composable carries
 * `MapSurfaceTestTags.Status`, and a second node with the same tag would break every test that
 * resolves a single status node - a harness affordance must not be able to fail the product's own
 * suite.
 *
 * Read once per composition from the stored value rather than from [GoogleFogCoverageArm], because
 * the point is to report what the surface was BOUND with. The initializer applies the stored arm
 * before any composition, so the two agree; if they ever disagree, the stored value is the one the
 * next process will use and the honest thing to show.
 */
@Composable
internal fun GoogleFogArmBadge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val arm = remember(context) { GoogleFogArm.stored(context) }
    Surface(
        modifier = modifier.padding(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.small,
        shadowElevation = 2.dp,
    ) {
        Text(
            text = "arm: ${arm.label}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
