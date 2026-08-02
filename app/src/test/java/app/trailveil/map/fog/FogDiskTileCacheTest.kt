package app.trailveil.map.fog

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FogDiskTileCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripUsesCompleteKeyAndReturnsEquivalentMask() {
        val cache = cache(maxBytes = 1_000)
        val key = key(x = 2, renderVersion = 4)
        val expected = mask(7)

        assertTrue(cache.put(key, expected))

        assertEquals(expected, cache.get(key))
        assertNull(cache.get(key.copy(renderVersion = 5)))
        assertEquals(FogDiskTileCacheStats(entryCount = 1, byteCount = 48), cache.stats())
    }

    @Test
    fun capacityIsBoundedAndRecentlyReadEntrySurvivesEviction() {
        val clock = AtomicLong(1_000)
        val cache = cache(maxBytes = 96, nowMillis = clock::incrementAndGet)
        val first = key(x = 0)
        val second = key(x = 1)
        val third = key(x = 2)

        assertTrue(cache.put(first, mask(1)))
        assertTrue(cache.put(second, mask(2)))
        assertEquals(mask(1), cache.get(first))
        assertTrue(cache.put(third, mask(3)))

        assertEquals(mask(1), cache.get(first))
        assertNull(cache.get(second))
        assertEquals(mask(3), cache.get(third))
        assertEquals(FogDiskTileCacheStats(entryCount = 2, byteCount = 96), cache.stats())
    }

    @Test
    fun truncatedOrCorruptEntriesBecomeMissesAndAreDeleted() {
        val cacheRoot = temporaryFolder.newFolder("corrupt-cache")
        val cache = FogDiskTileCache(cacheRoot, maxBytes = 1_000)
        val key = key(x = 3)
        assertTrue(cache.put(key, mask(5)))
        val entry = cacheRoot.walkTopDown().single { it.isFile && it.extension == "mask" }
        entry.writeBytes(entry.readBytes().copyOf(20))

        assertNull(cache.get(key))
        assertFalse(entry.exists())
        assertEquals(FogDiskTileCacheStats(entryCount = 0, byteCount = 0), cache.stats())
    }

    @Test
    fun checksumMismatchBecomesMissAndIsDeleted() {
        val cacheRoot = temporaryFolder.newFolder("checksum-cache")
        val cache = FogDiskTileCache(cacheRoot, maxBytes = 1_000)
        val key = key(x = 2)
        assertTrue(cache.put(key, mask(8)))
        val entry = cacheRoot.walkTopDown().single { it.isFile && it.extension == "mask" }
        val bytes = entry.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        entry.writeBytes(bytes)

        assertNull(cache.get(key))
        assertFalse(entry.exists())
    }

    @Test
    fun invalidateVersionRetentionAndClearRemoveOnlyDerivedFiles() {
        val cacheRoot = temporaryFolder.newFolder("retention-cache")
        val unrelatedMask = File(cacheRoot, "notes.mask").apply { writeText("keep") }
        val unrelatedTemporary = File(cacheRoot, ".unrelated.tmp").apply { writeText("keep") }
        val cache = FogDiskTileCache(cacheRoot, maxBytes = 1_000)
        val v1a = key(x = 0, renderVersion = 1)
        val v1b = key(x = 1, renderVersion = 1)
        val v2 = key(x = 2, renderVersion = 2)
        listOf(v1a, v1b, v2).forEach { assertTrue(cache.put(it, mask(it.x))) }

        assertEquals(FogDiskMutationResult(1, complete = true), cache.invalidate(listOf(v1a, v1a, key(x = 3))))
        assertEquals(FogDiskMutationResult(1, complete = true), cache.retainRenderVersion(2))
        assertEquals(mask(2), cache.get(v2))
        assertEquals(FogDiskTileCacheStats(entryCount = 1, byteCount = 48), cache.stats())

        cache.clear()
        assertEquals(FogDiskTileCacheStats(entryCount = 0, byteCount = 0), cache.stats())
        assertTrue(unrelatedMask.isFile)
        assertTrue(unrelatedTemporary.isFile)
    }

    @Test
    fun deletedDirectoryIsANormalMissAndCanBeRebuilt() {
        val cacheRoot = temporaryFolder.newFolder("rebuild-cache")
        val cache = FogDiskTileCache(cacheRoot, maxBytes = 1_000)
        val key = key(x = 1)
        assertTrue(cache.put(key, mask(1)))
        cacheRoot.deleteRecursively()

        assertNull(cache.get(key))
        assertTrue(cache.put(key, mask(9)))
        assertEquals(mask(9), cache.get(key))
    }

    @Test
    fun orphanTemporaryFileIsRemovedWhenCacheReopens() {
        val cacheRoot = temporaryFolder.newFolder("orphan-cache")
        val orphanDirectory = File(cacheRoot, "v1/z2/x0").apply { mkdirs() }
        val orphan = File(orphanDirectory, ".y1.mask.interrupted.tmp").apply {
            writeBytes(ByteArray(200))
        }

        val cache = FogDiskTileCache(cacheRoot, maxBytes = 100)

        assertFalse(orphan.exists())
        assertEquals(FogDiskTileCacheStats(entryCount = 0, byteCount = 0), cache.stats())
    }

    @Test
    fun oversizedReplacementRemovesStaleEntry() {
        val cache = cache(maxBytes = 46)
        val key = key(x = 0)
        assertTrue(cache.put(key, FogPixelMask(1, 1, byteArrayOf(1))))

        assertFalse(cache.put(key, mask(2)))

        assertNull(cache.get(key))
        assertEquals(FogDiskTileCacheStats(entryCount = 0, byteCount = 0), cache.stats())
    }

    @Test
    fun failedDeletionIsReportedAsAnIncompleteMutation() {
        val root = temporaryFolder.newFolder("failed-mutation")
        val key = key(x = 0)
        assertTrue(FogDiskTileCache(root, maxBytes = 1_000).put(key, mask(1)))
        val cache = FogDiskTileCache(
            rootDirectory = root,
            maxBytes = 1_000,
            deleteFile = { false },
        )

        assertEquals(
            FogDiskMutationResult(removedEntries = 0, complete = false),
            cache.invalidate(listOf(key)),
        )
        assertEquals(mask(1), cache.get(key))
    }

    private fun cache(
        maxBytes: Long,
        nowMillis: () -> Long = System::currentTimeMillis,
    ): FogDiskTileCache = FogDiskTileCache(
        rootDirectory = temporaryFolder.newFolder(),
        maxBytes = maxBytes,
        nowMillis = nowMillis,
    )

    private fun key(x: Int, renderVersion: Int = 0) =
        FogTileKey(zoom = 2, x = x, y = 1, renderVersion = renderVersion)

    private fun mask(alpha: Int) =
        FogPixelMask(width = 2, height = 2, alpha = ByteArray(4) { alpha.toByte() })
}
