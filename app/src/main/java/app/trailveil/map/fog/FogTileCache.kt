package app.trailveil.map.fog

object FogRenderVersions {
    /** Increment whenever renderer semantics or reveal styling invalidate derived masks. */
    const val CURRENT = 1
}

data class FogTileCacheStats(
    val entryCount: Int,
    val byteCount: Long,
)

/** Thread-safe, byte-bounded LRU for derived fog masks. Canonical tracks remain in Room. */
class FogMemoryTileCache(
    private val maxBytes: Long,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    private val entries = LinkedHashMap<FogTileKey, FogPixelMask>(16, 0.75f, true)
    private var byteCount = 0L

    @Synchronized
    fun get(key: FogTileKey): FogPixelMask? = entries[key]

    /** Returns false when one mask is too large to cache. Any stale value for [key] is removed. */
    @Synchronized
    fun put(key: FogTileKey, mask: FogPixelMask): Boolean {
        removeEntry(key)
        val maskBytes = mask.byteCount()
        if (maskBytes > maxBytes) return false

        entries[key] = mask
        byteCount = Math.addExact(byteCount, maskBytes)
        evictToLimit()
        return true
    }

    @Synchronized
    fun invalidate(keys: Collection<FogTileKey>): Int =
        keys.distinct().count { key -> removeEntry(key) != null }

    /** Drops masks produced by an older algorithm/style identity. */
    @Synchronized
    fun retainRenderVersion(renderVersion: Int): Int {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        val obsolete = entries.keys.filter { key -> key.renderVersion != renderVersion }
        obsolete.forEach(::removeEntry)
        return obsolete.size
    }

    @Synchronized
    fun clear() {
        entries.clear()
        byteCount = 0L
    }

    @Synchronized
    fun stats(): FogTileCacheStats = FogTileCacheStats(entries.size, byteCount)

    private fun FogPixelMask.byteCount(): Long =
        Math.multiplyExact(width.toLong(), height.toLong())

    private fun removeEntry(key: FogTileKey): FogPixelMask? =
        entries.remove(key)?.also { removed -> byteCount -= removed.byteCount() }

    private fun evictToLimit() {
        while (byteCount > maxBytes) {
            val eldestKey = entries.entries.iterator().next().key
            removeEntry(eldestKey)
        }
    }
}
