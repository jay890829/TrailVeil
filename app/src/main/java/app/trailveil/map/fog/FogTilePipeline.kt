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

    private fun cached(key: FogTileKey): FogTileLoad? {
        memoryCache.get(key)?.let { mask ->
            return FogTileLoad(mask, FogTileLoadSource.MEMORY)
        }
        runCatching { diskCache?.get(key) }.getOrNull()?.let { mask ->
            memoryCache.put(key, mask)
            return FogTileLoad(mask, FogTileLoadSource.DISK)
        }
        return null
    }

    private fun store(key: FogTileKey, mask: FogPixelMask): Boolean {
        val memoryStored = memoryCache.put(key, mask)
        val diskStored = diskCache?.let { cache ->
            runCatching { cache.put(key, mask) }.getOrDefault(false)
        } ?: false
        diskCache?.takeIf { !diskStored }?.let { cache ->
            runCatching { cache.invalidate(listOf(key)) }
        }
        return memoryStored || diskStored
    }

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
