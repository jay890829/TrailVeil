package app.trailveil.harness

import app.trailveil.map.fog.FogTileKey
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

class TrackUnionCacheTest {
    private val factory = GeometryFactory()
    private fun key(x: Int) = FogTileKey(12, x, 1, 1)
    private fun line(points: Int) = factory.createLineString(
        Array(points) { Coordinate(it.toDouble(), 0.0) },
    )

    @Test fun entryBudgetUsesAccessOrderEvenForEmptyUnionsAndCopiesKeys() {
        val cache = TrackUnionCache(maxEntries = 2)
        val a = mutableListOf(key(1))
        val empty = factory.createPolygon()
        cache.put(a, empty)
        a[0] = key(99)
        cache.put(listOf(key(2)), empty)
        assertSame(empty, cache[listOf(key(1))])
        cache.put(listOf(key(3)), empty)
        assertNull(cache[listOf(key(2))])
        assertSame(empty, cache[listOf(key(1))])
        assertEquals(setOf(key(1), key(3)), cache.partitionKeys)
        assertEquals(2, cache.size)
        assertEquals(0, cache.vertices)
    }

    @Test fun aggregateVertexBudgetIncludesEveryEntryAndReplacement() {
        val cache = TrackUnionCache(maxEntries = 4, maxVertices = 10)
        cache.put(listOf(key(1)), line(6))
        cache.put(listOf(key(2)), line(4))
        assertEquals(10, cache.vertices)
        cache.put(listOf(key(3)), line(3))
        assertNull(cache[listOf(key(1))])
        assertEquals(7, cache.vertices)
        cache.put(listOf(key(2)), line(7))
        assertEquals(10, cache.vertices)
        cache.put(listOf(key(2)), line(11))
        assertNull(cache[listOf(key(2))])
        assertEquals(3, cache.vertices)
        cache.clear()
        assertEquals(0, cache.vertices)
        assertEquals(0, cache.size)
        assertTrue(cache.partitionKeys.isEmpty())
    }

    @Test fun invalidationRemovesEveryDependentUnionOnly() {
        val cache = TrackUnionCache()
        val a = listOf(key(1), key(2))
        val b = listOf(key(2), key(3))
        val c = listOf(key(4))
        cache.put(a, line(2))
        cache.put(b, line(3))
        cache.put(c, line(4))
        cache.invalidate(setOf(key(2)))
        assertNull(cache[a])
        assertNull(cache[b])
        assertNotNull(cache[c])
        assertEquals(setOf(key(4)), cache.partitionKeys)
        assertEquals(4, cache.vertices)
        cache.invalidate(setOf(key(99)))
        assertEquals(1, cache.size)
    }
}
