package app.trailveil.map

import android.content.Context
import app.trailveil.map.fog.FogViewportCoordinator

/**
 * A distributed build renders the mosaic it has always rendered.
 *
 * `V03-013`'s mosaic-size arm is a measurement fixture and lives in `src/debug` alone, because
 * `src/mapLibre` is compiled by `release` as well and a selectable fog size must not reach a
 * published artifact. This twin answers with the shipped constant and cannot name an arm.
 */
@Suppress("UNUSED_PARAMETER")
internal fun fogMosaicPaddingTiles(context: Context): Int =
    FogViewportCoordinator.DEFAULT_MOSAIC_PADDING_TILES
