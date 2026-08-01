package app.trailveil.map.fog

enum class FogTileLoadSource {
    MEMORY,
    DISK,
    RENDERED,
}

data class FogTileLoad(
    val mask: FogPixelMask,
    val source: FogTileLoadSource,
)

data class FogCacheInvalidation(
    val memoryEntries: Int,
    val diskEntries: Int,
)

/**
 * Provider-neutral memory -> disk -> renderer chain for one complete fog mask.
 *
 * Calls are serialized so an instance does not render the same tile concurrently. Invoke [load]
 * from a controlled background dispatcher; while it is in flight, UI must keep an opaque fog
 * placeholder. Disk storage is optional derived state, so disk failures degrade to rendering.
 */
class FogTilePipeline(
    private val memoryCache: FogMemoryTileCache,
    private val diskCache: FogDiskTileCache?,
    private val renderMask: (FogTileKey, List<TrackSegment>) -> FogPixelMask,
) {
    @Synchronized
    fun load(key: FogTileKey, segments: List<TrackSegment>): FogTileLoad {
        memoryCache.get(key)?.let { mask ->
            return FogTileLoad(mask, FogTileLoadSource.MEMORY)
        }

        runCatching { diskCache?.get(key) }.getOrNull()?.let { mask ->
            memoryCache.put(key, mask)
            return FogTileLoad(mask, FogTileLoadSource.DISK)
        }

        val rendered = renderMask(key, segments)
        memoryCache.put(key, rendered)
        runCatching { diskCache?.put(key, rendered) }
        return FogTileLoad(rendered, FogTileLoadSource.RENDERED)
    }

    @Synchronized
    fun invalidate(keys: Collection<FogTileKey>): FogCacheInvalidation =
        FogCacheInvalidation(
            memoryEntries = memoryCache.invalidate(keys),
            diskEntries = runCatching { diskCache?.invalidate(keys) ?: 0 }.getOrDefault(0),
        )

    @Synchronized
    fun retainRenderVersion(renderVersion: Int): FogCacheInvalidation {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        return FogCacheInvalidation(
            memoryEntries = memoryCache.retainRenderVersion(renderVersion),
            diskEntries = runCatching {
                diskCache?.retainRenderVersion(renderVersion) ?: 0
            }.getOrDefault(0),
        )
    }

    @Synchronized
    fun clear() {
        memoryCache.clear()
        runCatching { diskCache?.clear() }
    }
}
