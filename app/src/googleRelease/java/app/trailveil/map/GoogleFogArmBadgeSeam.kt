package app.trailveil.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A published build has one fog design, so it has nothing to name.
 *
 * `V03-013`'s arm selector is a measurement fixture. This twin draws nothing and, like
 * [googleFogOverlayInstaller]'s release twin, **cannot name the arm type at all** - no enum, no
 * label, no stored preference is compiled into `googleRelease`. Declared once per build type
 * rather than switched on a flag, the idiom [ProductionMapProvider], [googleFogCoverageProfile]
 * and [googleFogOverlayInstaller] use, so exactly one of the two is compiled into any variant.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun GoogleFogArmBadge(
    modifier: Modifier = Modifier,
    lastCoverMillis: Long? = null,
    maximumCoverMillis: Long? = null,
) {
    // Intentionally empty; see above. The cover timings are accepted and dropped rather than left
    // out of the signature, so the shared call site stays one call rather than a per-build-type
    // one - the seam is the twin, not the site that uses it.
}
