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

data class FogRevealMerge(
    val updatedKeys: Set<FogTileKey>,
    val missingKeys: Set<FogTileKey>,
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
    private var diskCacheEnabled = diskCache != null

    /**
     * The cached mask for [key], or null when it would have to be rendered.
     *
     * This exists so a caller can find out what is missing BEFORE it pays for the canonical read
     * that rendering needs: at low zooms the tile window is the whole world, and reading the whole
     * track table to satisfy tiles that were already cached is the cost this answers.
     */
    @Synchronized
    fun loadCached(key: FogTileKey): FogTileLoad? = cached(key)

    /** Snapshot of the byte-bounded derived keys that can become stale after a reveal. */
    @Synchronized
    fun cachedKeys(): Set<FogTileKey> {
        val memoryKeys = memoryCache.keys()
        val diskKeys = activeDiskCache()?.let { cache ->
            runCatching { cache.keys() }
                .onFailure { diskCacheEnabled = false }
                .getOrNull()
        }.orEmpty()
        return memoryKeys + diskKeys
    }

    @Synchronized
    fun load(key: FogTileKey, segments: List<TrackSegment>): FogTileLoad {
        cached(key)?.let { return it }

        val rendered = renderMask(key, segments)
        store(key, rendered)
        return FogTileLoad(rendered, FogTileLoadSource.RENDERED)
    }

    /**
     * Unions one newly persisted reveal into complete cached masks.
     *
     * Missing tiles are never populated from the delta alone because that would omit canonical
     * history. Callers must rebuild [FogRevealMerge.missingKeys] from Room before display.
     */
    @Synchronized
    fun mergeReveal(
        keys: Collection<FogTileKey>,
        revealSegments: List<TrackSegment>,
    ): FogRevealMerge {
        val updated = linkedSetOf<FogTileKey>()
        val missing = linkedSetOf<FogTileKey>()
        keys.distinct().forEach { key ->
            val existing = cached(key)?.mask
            if (existing == null) {
                missing += key
            } else {
                val merged = try {
                    mergeMasks(existing, renderMask(key, revealSegments))
                } catch (failure: Exception) {
                    invalidate(listOf(key))
                    throw failure
                }
                if (store(key, merged)) {
                    updated += key
                } else {
                    invalidate(listOf(key))
                    missing += key
                }
            }
        }
        return FogRevealMerge(updatedKeys = updated, missingKeys = missing)
    }

    @Synchronized
    fun invalidate(keys: Collection<FogTileKey>): FogCacheInvalidation =
        FogCacheInvalidation(
            memoryEntries = memoryCache.invalidate(keys),
            diskEntries = activeDiskCache()?.let { cache ->
                runCatching { cache.invalidate(keys) }
                    .onFailure { diskCacheEnabled = false }
                    .getOrNull()
                    ?.also { result ->
                        if (!result.complete) diskCacheEnabled = false
                    }
                    ?.removedEntries ?: 0
            } ?: 0,
        )

    @Synchronized
    fun retainRenderVersion(renderVersion: Int): FogCacheInvalidation {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        return FogCacheInvalidation(
            memoryEntries = memoryCache.retainRenderVersion(renderVersion),
            diskEntries = activeDiskCache()?.let { cache ->
                runCatching { cache.retainRenderVersion(renderVersion) }
                    .onFailure { diskCacheEnabled = false }
                    .getOrNull()
                    ?.also { result ->
                        if (!result.complete) diskCacheEnabled = false
                    }
                    ?.removedEntries ?: 0
            } ?: 0,
        )
    }

    @Synchronized
    fun clear() {
        memoryCache.clear()
        val cache = activeDiskCache() ?: return
        val cleared = runCatching { cache.clear() }
            .onFailure { diskCacheEnabled = false }
            .getOrDefault(false)
        if (!cleared) diskCacheEnabled = false
    }

    private fun cached(key: FogTileKey): FogTileLoad? {
        memoryCache.get(key)?.let { mask ->
            return FogTileLoad(mask, FogTileLoadSource.MEMORY)
        }
        val diskMask = activeDiskCache()?.let { cache ->
            runCatching { cache.get(key) }
                .onFailure { diskCacheEnabled = false }
                .getOrNull()
        }
        diskMask?.let { mask ->
            memoryCache.put(key, mask)
            return FogTileLoad(mask, FogTileLoadSource.DISK)
        }
        return null
    }

    private fun store(key: FogTileKey, mask: FogPixelMask): Boolean {
        val memoryStored = memoryCache.put(key, mask)
        val cache = activeDiskCache()
        val diskStored = cache?.let {
            runCatching { it.put(key, mask) }
                .onFailure { diskCacheEnabled = false }
                .getOrDefault(false)
        } ?: false
        cache?.takeIf { !diskStored && diskCacheEnabled }?.let {
            runCatching { it.invalidate(listOf(key)) }
                .onFailure { diskCacheEnabled = false }
                .getOrNull()
                ?.takeIf { result -> !result.complete }
                ?.let { diskCacheEnabled = false }
        }
        return memoryStored || diskStored
    }

    private fun activeDiskCache(): FogDiskTileCache? =
        diskCache.takeIf { diskCacheEnabled }

    private fun mergeMasks(existing: FogPixelMask, delta: FogPixelMask): FogPixelMask {
        require(existing.width == delta.width && existing.height == delta.height) {
            "fog reveal delta dimensions must match the cached mask"
        }
        val existingAlpha = existing.copyAlpha()
        val deltaAlpha = delta.copyAlpha()
        val merged = ByteArray(existingAlpha.size) { index ->
            minOf(
                existingAlpha[index].toInt() and 0xff,
                deltaAlpha[index].toInt() and 0xff,
            ).toByte()
        }
        return FogPixelMask(existing.width, existing.height, merged)
    }
}
