package app.trailveil.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.trailveil.map.fog.FogRuntime
import com.google.android.gms.maps.GoogleMap

/**
 * A published build fogs the ground, always, and has no arm that turns that off.
 *
 * `V03-013`'s `screenStencil` is the one arm that removes the canonical fog instead of swapping
 * it, so it is the one this seam most needs to keep out. [googleFogStencilActive] answers false
 * unconditionally - a published build cannot even ask - and the overlay twin draws nothing and
 * **cannot name the layer it would have drawn**: no stencil composable, no per-frame projection
 * read, no reveal query is compiled into `googleRelease`.
 *
 * Declared once per build type rather than switched on a flag, the idiom [ProductionMapProvider],
 * [googleFogCoverageProfile] and [googleFogOverlayInstaller] use.
 */
internal fun googleFogStencilActive(): Boolean = false

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun GoogleFogStencilOverlay(
    map: GoogleMap?,
    fogRuntime: FogRuntime?,
    modifier: Modifier = Modifier,
) {
    // Intentionally empty; see above.
}
