package app.trailveil.harness

import app.trailveil.map.fog.FogTileKey
import org.locationtech.jts.geom.Geometry

/**
 * Small process-local LRU for complete partition unions. The caller owns serialization and must
 * never mutate a retained geometry. Keys are copied; viewport clipping is not part of this cache.
 * Both entry and aggregate vertex limits apply, including empty unions with zero vertices.
 */
internal class TrackUnionCache(
    private val maxEntries: Int = 4,
    private val maxVertices: Int = 40_000,
) {
    private val entries = LinkedHashMap<List<FogTileKey>, Geometry>(4, 0.75f, true)
    var vertices: Int = 0
        private set
    val size: Int get() = entries.size
    val partitionKeys: Set<FogTileKey> get() = entries.keys.flatten().toSet()

    init {
        require(maxEntries > 0 && maxVertices >= 0)
    }

    operator fun get(keys: List<FogTileKey>): Geometry? = entries[keys]

    fun put(keys: List<FogTileKey>, geometry: Geometry) {
        entries.remove(keys)?.let { vertices -= it.numPoints }
        if (geometry.numPoints > maxVertices) return
        entries[keys.toList()] = geometry
        vertices += geometry.numPoints
        while (entries.size > maxEntries || vertices > maxVertices) {
            val iterator = entries.entries.iterator()
            vertices -= iterator.next().value.numPoints
            iterator.remove()
        }
    }

    fun invalidate(dirty: Set<FogTileKey>) {
        if (dirty.isEmpty()) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.any { it in dirty }) {
                vertices -= entry.value.numPoints
                iterator.remove()
            }
        }
    }

    fun clear() {
        entries.clear()
        vertices = 0
    }
}
