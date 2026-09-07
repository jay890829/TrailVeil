package app.trailveil.map

import android.content.Context
import app.trailveil.map.fog.FogViewportCoordinator

/**
 * This provider's twin: the legacy mosaic, unconditionally.
 *
 * `V03-013`'s mosaic-size arm belongs to the other provider, whose fog IS that mosaic. This
 * provider does not reach `FogViewportCoordinator.render` for its viewport at all - it uses the
 * batch seam `renderTiles`, planned by `GoogleFogCoverageProfile`, and its own size arm is the
 * padding ring in that profile. Changing the mosaic here would resize something this provider
 * only uses as a bootstrap, so the value is fixed rather than selectable.
 *
 * Declared once per build type rather than switched on a flag - the idiom [ProductionMapProvider]
 * and [googleFogCoverageProfile] use - so exactly one twin is compiled into any variant. Lives in
 * `src/google` because both Google build types answer the same way.
 */
@Suppress("UNUSED_PARAMETER")
internal fun fogMosaicPaddingTiles(context: Context): Int =
    FogViewportCoordinator.DEFAULT_MOSAIC_PADDING_TILES
