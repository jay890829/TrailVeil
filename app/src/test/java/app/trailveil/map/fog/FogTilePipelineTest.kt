package app.trailveil.map.fog

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FogTilePipelineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun renderedMaskFlowsThroughMemoryThenDisk() {
        val memory = FogMemoryTileCache(maxBytes = 64)
        val disk = FogDiskTileCache(temporaryFolder.newFolder(), maxBytes = 1_000)
        val renderCount = AtomicInteger()
        val expected = mask(7)
        val pipeline = pipeline(memory, disk, renderCount, expected)
        val key = key(x = 1)

        assertEquals(FogTileLoad(expected, FogTileLoadSource.RENDERED), pipeline.load(key, segments()))
        assertEquals(FogTileLoad(expected, FogTileLoadSource.MEMORY), pipeline.load(key, emptyList()))
        memory.clear()
        assertEquals(FogTileLoad(expected, FogTileLoadSource.DISK), pipeline.load(key, emptyList()))
        assertEquals(1, renderCount.get())
    }

    @Test
    fun exactInvalidationRemovesBothLayersAndForcesRender() {
        val memory = FogMemoryTileCache(maxBytes = 64)
        val disk = FogDiskTileCache(temporaryFolder.newFolder(), maxBytes = 1_000)
        val renderCount = AtomicInteger()
        val pipeline = pipeline(memory, disk, renderCount, mask(3))
        val target = key(x = 0)
        val other = key(x = 1)
        pipeline.load(target, segments())
        pipeline.load(other, segments())

        assertEquals(
            FogCacheInvalidation(memoryEntries = 1, diskEntries = 1),
            pipeline.invalidate(listOf(target, target)),
        )
        assertEquals(FogTileLoadSource.RENDERED, pipeline.load(target, segments()).source)
        assertEquals(FogTileLoadSource.MEMORY, pipeline.load(other, emptyList()).source)
        assertEquals(3, renderCount.get())
    }

    @Test
    fun renderVersionRetentionIsAppliedToBothLayers() {
        val memory = FogMemoryTileCache(maxBytes = 64)
        val disk = FogDiskTileCache(temporaryFolder.newFolder(), maxBytes = 1_000)
        val renderCount = AtomicInteger()
        val pipeline = pipeline(memory, disk, renderCount, mask(4))
        val old = key(x = 0, renderVersion = 1)
        val current = key(x = 1, renderVersion = 2)
        pipeline.load(old, segments())
        pipeline.load(current, segments())

        assertEquals(
            FogCacheInvalidation(memoryEntries = 1, diskEntries = 1),
            pipeline.retainRenderVersion(2),
        )
        assertEquals(FogTileLoadSource.RENDERED, pipeline.load(old, segments()).source)
        assertEquals(FogTileLoadSource.MEMORY, pipeline.load(current, emptyList()).source)
        assertEquals(3, renderCount.get())
    }

    @Test
    fun diskWriteFailureFallsBackToRenderingAndMemory() {
        val memory = FogMemoryTileCache(maxBytes = 64)
        val unusableRoot = temporaryFolder.newFile("not-a-directory")
        val disk = FogDiskTileCache(unusableRoot, maxBytes = 1_000)
        val renderCount = AtomicInteger()
        val expected = mask(9)
        val pipeline = pipeline(memory, disk, renderCount, expected)
        val key = key(x = 2)

        assertEquals(FogTileLoad(expected, FogTileLoadSource.RENDERED), pipeline.load(key, segments()))
        assertEquals(FogTileLoad(expected, FogTileLoadSource.MEMORY), pipeline.load(key, emptyList()))
        assertEquals(1, renderCount.get())
    }

    @Test
    fun revealDeltaUnionsWithCompleteMemoryAndDiskMasks() {
        val memory = FogMemoryTileCache(maxBytes = 64)
        val disk = FogDiskTileCache(temporaryFolder.newFolder(), maxBytes = 1_000)
        val renderCount = AtomicInteger()
        val base = alphaMask(0, 184, 184, 184)
        val delta = alphaMask(184, 0, 184, 184)
        val combined = alphaMask(0, 0, 184, 184)
        val pipeline = FogTilePipeline(memory, disk) { _, segments ->
            renderCount.incrementAndGet()
            if (segments.single().id == 1) base else delta
        }
        val key = key(x = 0)
        pipeline.load(key, segment(id = 1))

        assertEquals(
            FogRevealMerge(updatedKeys = setOf(key), missingKeys = emptySet()),
            pipeline.mergeReveal(listOf(key, key), segment(id = 2)),
        )
        assertArrayEquals(
            combined.copyAlpha(),
            pipeline.load(key, emptyList()).mask.copyAlpha(),
        )
        memory.clear()
        val diskLoad = pipeline.load(key, emptyList())
        assertEquals(FogTileLoadSource.DISK, diskLoad.source)
        assertArrayEquals(combined.copyAlpha(), diskLoad.mask.copyAlpha())
        assertEquals(2, renderCount.get())
    }

    @Test
    fun revealDeltaNeverPopulatesAnIncompleteCacheMiss() {
        val memory = FogMemoryTileCache(maxBytes = 64)
        val disk = FogDiskTileCache(temporaryFolder.newFolder(), maxBytes = 1_000)
        val renderCount = AtomicInteger()
        val pipeline = pipeline(memory, disk, renderCount, mask(1))
        val key = key(x = 1)

        assertEquals(
            FogRevealMerge(updatedKeys = emptySet(), missingKeys = setOf(key)),
            pipeline.mergeReveal(listOf(key), segment(id = 2)),
        )
        assertEquals(0, renderCount.get())
        assertEquals(FogTileCacheStats(entryCount = 0, byteCount = 0), memory.stats())
        assertEquals(FogDiskTileCacheStats(entryCount = 0, byteCount = 0), disk.stats())
    }

    @Test
    fun incompatibleRevealDeltaInvalidatesStaleCopies() {
        val memory = FogMemoryTileCache(maxBytes = 64)
        val disk = FogDiskTileCache(temporaryFolder.newFolder(), maxBytes = 1_000)
        val pipeline = FogTilePipeline(memory, disk) { _, segments ->
            if (segments.single().id == 1) mask(1) else FogPixelMask(1, 1, byteArrayOf(0))
        }
        val key = key(x = 2)
        pipeline.load(key, segment(id = 1))

        assertThrows(IllegalArgumentException::class.java) {
            pipeline.mergeReveal(listOf(key), segment(id = 2))
        }
        assertEquals(FogTileCacheStats(entryCount = 0, byteCount = 0), memory.stats())
        assertEquals(FogDiskTileCacheStats(entryCount = 0, byteCount = 0), disk.stats())
    }

    @Test
    fun clearDropsDerivedLayersWithoutChangingTheRendererInput() {
        val memory = FogMemoryTileCache(maxBytes = 64)
        val disk = FogDiskTileCache(temporaryFolder.newFolder(), maxBytes = 1_000)
        val renderCount = AtomicInteger()
        val expectedSegments = segments()
        var latestSegments: List<TrackSegment>? = null
        val pipeline = FogTilePipeline(memory, disk) { _, segments ->
            renderCount.incrementAndGet()
            latestSegments = segments
            mask(1)
        }
        val key = key(x = 0)
        pipeline.load(key, expectedSegments)

        pipeline.clear()
        assertEquals(FogTileLoadSource.RENDERED, pipeline.load(key, expectedSegments).source)
        assertEquals(expectedSegments, latestSegments)
        assertEquals(2, renderCount.get())
    }

    private fun pipeline(
        memory: FogMemoryTileCache,
        disk: FogDiskTileCache,
        renderCount: AtomicInteger,
        rendered: FogPixelMask,
    ) = FogTilePipeline(memory, disk) { _, _ ->
        renderCount.incrementAndGet()
        rendered
    }

    private fun key(x: Int, renderVersion: Int = FogRenderVersions.CURRENT) =
        FogTileKey(zoom = 2, x = x, y = 1, renderVersion = renderVersion)

    private fun mask(alpha: Int) =
        FogPixelMask(width = 2, height = 2, alpha = ByteArray(4) { alpha.toByte() })

    private fun alphaMask(vararg alpha: Int) =
        FogPixelMask(width = 2, height = 2, alpha = ByteArray(alpha.size) { alpha[it].toByte() })

    private fun segment(id: Int) = listOf(
        TrackSegment(id = id, points = listOf(GeoPoint(latitude = 25.0, longitude = 121.0))),
    )

    private fun segments() = listOf(
        TrackSegment(id = 1, points = listOf(GeoPoint(latitude = 25.0, longitude = 121.0))),
    )
}
