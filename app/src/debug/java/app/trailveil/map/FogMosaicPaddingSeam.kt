package app.trailveil.map

import android.content.Context

/**
 * The process-wide answer to "is the vector arm on", for the one caller with no Context.
 *
 * The fog layers are installed from a `Style` extension deep inside the map surface, which has no
 * Context to read a preference with. This is primed by [fogMosaicPaddingTiles], which the app
 * container calls once when it builds the fog runtime - before any map surface exists, because the
 * surface is handed that runtime. So the ordering is a consequence of the dependency rather than a
 * hope about it.
 */
internal object MapLibreVectorFogState {
    @Volatile
    var enabled: Boolean = false
}

/**
 * The harness twin: the mosaic this build's fog runtime is constructed with.
 *
 * Lives in `src/debug` rather than `src/mapLibre` because that tree is compiled by `release` too,
 * and a published artifact must not carry a selectable fog size. `internal` and `release` declare
 * the constant twin instead.
 *
 * Also primes [MapLibreVectorFogState]; see its KDoc for why that ordering holds.
 */
internal fun fogMosaicPaddingTiles(context: Context): Int {
    val arm = MapLibreFogArm.stored(context)
    MapLibreVectorFogState.enabled = arm.vectorFog
    return arm.mosaicPaddingTiles
}
