package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FogTileCacheTest {
    @Test
    fun leastRecentlyUsedEntryIsEvictedWithinByteLimit() {
        val cache = FogMemoryTileCache(maxBytes = 8)
        val first = key(x = 0)
        val second = key(x = 1)
        val third = key(x = 2)
        val firstMask = mask(1)

        assertTrue(cache.put(first, firstMask))
        assertTrue(cache.put(second, mask(2)))
        assertSame(firstMask, cache.get(first))
        assertTrue(cache.put(third, mask(3)))

        assertSame(firstMask, cache.get(first))
        assertNull(cache.get(second))
        assertEquals(FogTileCacheStats(entryCount = 2, byteCount = 8), cache.stats())
    }

    @Test
    fun oversizedReplacementRemovesStaleEntryAndIsNotCached() {
        val cache = FogMemoryTileCache(maxBytes = 4)
        val key = key(x = 0)
        assertTrue(cache.put(key, mask(1)))

        assertFalse(
            cache.put(
                key,
                FogPixelMask(width = 3, height = 3, alpha = ByteArray(9)),
            ),
        )

        assertNull(cache.get(key))
        assertEquals(FogTileCacheStats(entryCount = 0, byteCount = 0), cache.stats())
    }

    @Test
    fun exactInvalidationAndVersionRetentionKeepAccountingConsistent() {
        val cache = FogMemoryTileCache(maxBytes = 20)
        val v1a = key(x = 0, renderVersion = 1)
        val v1b = key(x = 1, renderVersion = 1)
        val v2 = key(x = 2, renderVersion = 2)
        listOf(v1a, v1b, v2).forEachIndexed { index, key ->
            cache.put(key, mask(index))
        }

        assertEquals(1, cache.invalidate(listOf(v1a, v1a, key(x = 3))))
        assertEquals(1, cache.retainRenderVersion(2))
        assertNotNull(cache.get(v2))
        assertEquals(FogTileCacheStats(entryCount = 1, byteCount = 4), cache.stats())

        cache.clear()
        assertEquals(FogTileCacheStats(entryCount = 0, byteCount = 0), cache.stats())
    }

    private fun key(x: Int, renderVersion: Int = 0) =
        FogTileKey(zoom = 2, x = x, y = 0, renderVersion = renderVersion)

    private fun mask(alpha: Int) =
        FogPixelMask(width = 2, height = 2, alpha = ByteArray(4) { alpha.toByte() })
}
