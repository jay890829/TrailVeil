package app.trailveil.map

import android.content.Context

/**
 * The harness twin: the mosaic this build's fog runtime is constructed with.
 *
 * Lives in `src/debug` rather than `src/mapLibre` because that tree is compiled by `release` too,
 * and a published artifact must not carry a selectable fog size. `internal` and `release` declare
 * the constant twin instead.
 */
internal fun fogMosaicPaddingTiles(context: Context): Int =
    MapLibreFogArm.stored(context).mosaicPaddingTiles
