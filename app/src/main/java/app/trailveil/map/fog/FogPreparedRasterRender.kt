package app.trailveil.map.fog

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keeps the full raster on fallback; a prepared native payload may discard only unused padding. */
internal suspend fun renderFogWithRequiredMasks(
    requested: Set<FogTileKey>,
    required: Set<FogTileKey>,
    preferRequired: Boolean,
    maxTiles: Int,
    render: suspend (Set<FogTileKey>) -> Map<FogTileKey, FogPixelMask>,
    prepare: suspend (Map<FogTileKey, FogPixelMask>) -> Boolean,
): Map<FogTileKey, FogPixelMask> {
    if (!preferRequired || required == requested) {
        val masks = render(requested)
        prepare(masks)
        currentCoroutineContext().ensureActive()
        return masks
    }
    // Validate the original window even when it will not be rendered. Cyclic-X completion can
    // choose a different arc after keys are removed; only a strict completed subset is eligible.
    val planner = FogRequestedTileWindowPlanner(maxTiles)
    val full = planner.plan(requested).keys
    require(requested.containsAll(required)) { "required masks must belong to the original request" }
    val reduced = try { planner.plan(required).keys } catch (_: IllegalArgumentException) { full }
    val useReduced = reduced.size < full.size && full.containsAll(reduced) &&
        full.filter { it in reduced } == reduced.toList()
    currentCoroutineContext().ensureActive()
    val masks = render(if (useReduced) required else requested)
    currentCoroutineContext().ensureActive()
    val nativeReady = prepare(masks)
    currentCoroutineContext().ensureActive()
    val result = if (useReduced && !nativeReady) render(requested) else masks
    currentCoroutineContext().ensureActive()
    return result
}
