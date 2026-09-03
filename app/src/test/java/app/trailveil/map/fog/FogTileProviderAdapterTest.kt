package app.trailveil.map.fog

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogTileProviderAdapterTest {
    @Test
    fun paletteRejectsPlaceholderAndAdjacentGenerationsUnderTheRealTolerance() {
        val colours = (1L..63L).map(FogTilePngCodec::colorForGeneration)

        assertEquals(63, colours.toSet().size)
        assertTrue(colours.all { colour ->
            colour.red - FogTilePngCodec.DEFAULT_FOG_COLOR.red in 0..12 &&
                colour.green - FogTilePngCodec.DEFAULT_FOG_COLOR.green in 0..12 &&
                colour.blue - FogTilePngCodec.DEFAULT_FOG_COLOR.blue in 0..12
        })
        (1L..64L).forEach { generation ->
            val current = FogTilePngCodec.colorForGeneration(generation)
            assertTrue(FogTilePngCodec.matchesGenerationColor(current, generation))
            assertFalse(
                FogTilePngCodec.matchesGenerationColor(
                    FogTilePngCodec.DEFAULT_FOG_COLOR,
                    generation,
                ),
            )
            if (generation > 1L) {
                assertFalse(
                    FogTilePngCodec.matchesGenerationColor(
                        FogTilePngCodec.colorForGeneration(generation - 1L),
                        generation,
                    ),
                )
            }
        }
        assertEquals(
            FogTilePngCodec.colorForGeneration(1L),
            FogTilePngCodec.colorForGeneration(64L),
        )
        assertFalse(FogTilePngCodec.generationStartsNewPaletteCycle(63L))
        assertTrue(FogTilePngCodec.generationStartsNewPaletteCycle(64L))
    }

    @Test
    fun encodedCanonicalMaskIsA256By256PngWithItsAlpha() {
        val alpha = ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE) {
            FogRenderStyle().fogAlpha.toByte()
        }
        alpha[0] = 0
        val mask = FogPixelMask(FogTilePngCodec.TILE_SIZE, FogTilePngCodec.TILE_SIZE, alpha)
        val adapter = adapterFor { mask }
        val generation = adapter.beginGeneration()

        assertTrue(adapter.publish(generation, listOf(key(2, 1, 1))))
        val image = decode(adapter.tileBytes(x = 1, y = 1, zoom = 2))

        assertEquals(FogTilePngCodec.TILE_SIZE, image.width)
        assertEquals(FogTilePngCodec.TILE_SIZE, image.height)
        assertEquals(0, alphaAt(image, 0, 0))
        assertEquals(255, alphaAt(image, 1, 0))
    }

    @Test
    fun missingErrorsAndWrongDimensionsAreFullyOpaquePlaceholders() {
        val cases = listOf<CanonicalFogTileSource>(
            CanonicalFogTileSource { null },
            CanonicalFogTileSource { error("canonical read failed") },
            CanonicalFogTileSource { FogPixelMask(1, 1, byteArrayOf(0)) },
        )

        cases.forEach { source ->
            val adapter = FogTileProviderAdapter(source)
            val generation = adapter.beginGeneration()
            assertTrue(adapter.publish(generation, listOf(key(2, 1, 1))))
            val image = decode(adapter.tileBytes(x = 1, y = 1, zoom = 2))
            assertTrue(
                "placeholder must be opaque",
                (0 until image.height).all { y ->
                    (0 until image.width).all { x -> alphaAt(image, x, y) == 255 }
                },
            )
        }
    }

    @Test
    fun invalidYAndUnsupportedZoomFailClosedWithoutCanonicalReads() {
        var sourceCalls = 0
        val adapter = adapterFor {
            sourceCalls += 1
            FogPixelMask(
                FogTilePngCodec.TILE_SIZE,
                FogTilePngCodec.TILE_SIZE,
                ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE),
            )
        }

        listOf(
            Triple(0, -1, 2),
            Triple(0, 4, 2),
            Triple(0, 0, -1),
            Triple(0, 0, 23),
        ).forEach { (x, y, zoom) ->
            val image = decode(adapter.tileBytes(x, y, zoom))
            assertEquals(255, alphaAt(image, 0, 0))
        }
        assertEquals(0, sourceCalls)
    }

    @Test
    fun xWorldCopiesAndDatelineNormalizeWithFloorMod() {
        val canonical = FogPixelMask(
            FogTilePngCodec.TILE_SIZE,
            FogTilePngCodec.TILE_SIZE,
            ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE),
        )
        val adapter = adapterFor { key ->
            assertEquals(3, key.x)
            assertEquals(1, key.y)
            canonical
        }
        val generation = adapter.beginGeneration()
        assertTrue(adapter.publish(generation, listOf(key(2, 3, 1))))

        val negativeCopy = adapter.tileBytes(x = -1, y = 1, zoom = 2)
        val positiveCopy = adapter.tileBytes(x = 7, y = 1, zoom = 2)
        val canonicalTile = adapter.tileBytes(x = 3, y = 1, zoom = 2)

        assertArrayEquals(canonicalTile, negativeCopy)
        assertArrayEquals(canonicalTile, positiveCopy)
        assertEquals(255, alphaAt(decode(adapter.tileBytes(x = 4, y = 1, zoom = 2)), 0, 0))
    }

    @Test
    fun tileResponseNamesOnlyBytesFromTheAtomicallyPublishedGeneration() {
        val clearMask = FogPixelMask(
            FogTilePngCodec.TILE_SIZE,
            FogTilePngCodec.TILE_SIZE,
            ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE),
        )
        val adapter = adapterFor { clearMask }
        val beforePublish = adapter.tileResponse(x = -1, y = 1, zoom = 2)

        assertEquals(key(2, 3, 1), beforePublish.key)
        assertEquals(null, beforePublish.publishedGeneration)

        val generation = adapter.beginGeneration()
        assertTrue(adapter.publish(generation, listOf(key(2, 3, 1))))
        val canonicalWorldCopy = adapter.tileResponse(x = 7, y = 1, zoom = 2)
        val absentCanonicalTile = adapter.tileResponse(x = 0, y = 1, zoom = 2)
        val invalidTile = adapter.tileResponse(x = 0, y = -1, zoom = 2)

        assertEquals(key(2, 3, 1), canonicalWorldCopy.key)
        assertEquals(generation.id, canonicalWorldCopy.publishedGeneration)
        assertEquals(key(2, 0, 1), absentCanonicalTile.key)
        assertEquals(null, absentCanonicalTile.publishedGeneration)
        assertEquals(null, invalidTile.key)
        assertEquals(null, invalidTile.publishedGeneration)
    }

    @Test
    fun staleGenerationCannotPublishClearCoverage() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clearMask = FogPixelMask(
            FogTilePngCodec.TILE_SIZE,
            FogTilePngCodec.TILE_SIZE,
            ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE),
        )
        val adapter = adapterFor {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            clearMask
        }
        val oldGeneration = adapter.beginGeneration()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                adapter.publish(oldGeneration, listOf(key(2, 1, 1)))
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            val newGeneration = adapter.beginGeneration()
            release.countDown()

            assertFalse(result.get(2, TimeUnit.SECONDS))
            assertEquals(255, alphaAt(decode(adapter.tileBytes(1, 1, 2)), 0, 0))
            assertTrue(adapter.publish(newGeneration, listOf(key(2, 1, 1))))
            assertEquals(0, alphaAt(decode(adapter.tileBytes(1, 1, 2)), 0, 0))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationRevokesPreviouslyPublishedCoverage() {
        val clearMask = FogPixelMask(
            FogTilePngCodec.TILE_SIZE,
            FogTilePngCodec.TILE_SIZE,
            ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE),
        )
        val adapter = adapterFor { clearMask }
        val generation = adapter.beginGeneration()
        assertTrue(adapter.publish(generation, listOf(key(2, 1, 1))))
        assertEquals(0, alphaAt(decode(adapter.tileBytes(1, 1, 2)), 0, 0))

        assertTrue(generation.cancel())
        assertEquals(255, alphaAt(decode(adapter.tileBytes(1, 1, 2)), 0, 0))
        assertEquals(null, adapter.cacheSnapshot().publishedGeneration)
    }

    @Test
    fun canonicalGapRemainsFogAndDoesNotBecomeClearCoverage() {
        val revealed = ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE) {
            FogRenderStyle().fogAlpha.toByte()
        }
        revealed[0] = 0
        val gap = FogPixelMask(
            FogTilePngCodec.TILE_SIZE,
            FogTilePngCodec.TILE_SIZE,
            ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE) {
                FogRenderStyle().fogAlpha.toByte()
            },
        )
        val adapter = adapterFor { key ->
            when (key.x) {
                1 -> FogPixelMask(FogTilePngCodec.TILE_SIZE, FogTilePngCodec.TILE_SIZE, revealed)
                else -> gap
            }
        }
        val generation = adapter.beginGeneration()
        assertTrue(adapter.publish(generation, listOf(key(2, 1, 1), key(2, 2, 1))))

        assertEquals(0, alphaAt(decode(adapter.tileBytes(1, 1, 2)), 0, 0))
        assertEquals(
            255,
            alphaAt(decode(adapter.tileBytes(2, 1, 2)), 0, 0),
        )
    }

    @Test
    fun encodedCacheRejectsAnOverBudgetGenerationAtomically() {
        val clearMask = FogPixelMask(
            FogTilePngCodec.TILE_SIZE,
            FogTilePngCodec.TILE_SIZE,
            ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE),
        )
        val adapter = FogTileProviderAdapter(
            source = CanonicalFogTileSource { clearMask },
            cacheBudget = FogTileCacheBudget(maxEntries = 1, maxBytes = 1_024 * 1_024),
        )
        val generation = adapter.beginGeneration()

        assertFalse(adapter.publish(generation, listOf(key(2, 1, 1), key(2, 2, 1))))
        assertEquals(0, adapter.cacheSnapshot().entryCount)
        assertEquals(255, alphaAt(decode(adapter.tileBytes(1, 1, 2)), 0, 0))
    }

    // ---- handover generations (V02-005 design §2.2) ------------------------------------------

    @Test
    fun handoverKeepsThePreviousPublishedBytesServingWhilePending() {
        val mask = fullyOpaqueMask()
        val adapter = adapterFor { mask }
        val first = adapter.beginGeneration()
        assertTrue(adapter.publish(first, listOf(key(2, 1, 1))))
        val servedBefore = adapter.tileResponse(x = 1, y = 1, zoom = 2)
        assertEquals(first.id, servedBefore.publishedGeneration)

        val pending = adapter.beginHandoverGeneration()
        val servedDuring = adapter.tileResponse(x = 1, y = 1, zoom = 2)
        assertEquals(
            "the prior published set keeps serving while the handover renders",
            first.id,
            servedDuring.publishedGeneration,
        )
        assertArrayEquals(servedBefore.bytes, servedDuring.bytes)
        assertTrue(adapter.isCurrent(pending))
        assertFalse(adapter.isCurrent(first))
    }

    @Test
    fun handoverPublishAtomicallySwapsToTheNewGeneration() {
        val mask = fullyOpaqueMask()
        val adapter = adapterFor { mask }
        val first = adapter.beginGeneration()
        assertTrue(adapter.publish(first, listOf(key(2, 1, 1))))

        val pending = adapter.beginHandoverGeneration()
        assertTrue(adapter.publish(pending, listOf(key(2, 1, 1), key(2, 2, 1))))
        val served = adapter.tileResponse(x = 1, y = 1, zoom = 2)
        assertEquals(pending.id, served.publishedGeneration)
        assertEquals(2, adapter.cacheSnapshot().entryCount)
    }

    @Test
    fun cancellingAPendingHandoverLeavesThePublishedSetIntact() {
        val mask = fullyOpaqueMask()
        val adapter = adapterFor { mask }
        val first = adapter.beginGeneration()
        assertTrue(adapter.publish(first, listOf(key(2, 1, 1))))

        val pending = adapter.beginHandoverGeneration()
        assertTrue(pending.cancel())
        val served = adapter.tileResponse(x = 1, y = 1, zoom = 2)
        assertEquals(
            "a dead pending handover must not revoke proven coverage",
            first.id,
            served.publishedGeneration,
        )
        assertFalse("a stale pending handover cannot publish", adapter.publish(pending, listOf(key(2, 1, 1))))
        assertEquals(first.id, adapter.tileResponse(x = 1, y = 1, zoom = 2).publishedGeneration)
    }

    @Test
    fun cancellingAHandoverThatAlreadySwappedFailsClosed() {
        val mask = fullyOpaqueMask()
        val adapter = adapterFor { mask }
        val first = adapter.beginGeneration()
        assertTrue(adapter.publish(first, listOf(key(2, 1, 1))))
        val pending = adapter.beginHandoverGeneration()
        assertTrue(adapter.publish(pending, listOf(key(2, 1, 1))))

        assertTrue(pending.cancel())
        assertEquals(
            "cancelling a published handover must never leave disproven tiles serving",
            null,
            adapter.tileResponse(x = 1, y = 1, zoom = 2).publishedGeneration,
        )
    }

    @Test
    fun revokePathStillClearsImmediatelyForFirstInstallAndFailureRecovery() {
        val mask = fullyOpaqueMask()
        val adapter = adapterFor { mask }
        val first = adapter.beginGeneration()
        assertTrue(adapter.publish(first, listOf(key(2, 1, 1))))

        adapter.beginGeneration()
        assertEquals(
            "beginGeneration revokes the previous coverage on the spot",
            null,
            adapter.tileResponse(x = 1, y = 1, zoom = 2).publishedGeneration,
        )
    }

    private fun fullyOpaqueMask(): FogPixelMask {
        val alpha = ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE) {
            FogRenderStyle().fogAlpha.toByte()
        }
        return FogPixelMask(FogTilePngCodec.TILE_SIZE, FogTilePngCodec.TILE_SIZE, alpha)
    }

    private fun adapterFor(source: CanonicalFogTileSource): FogTileProviderAdapter =
        FogTileProviderAdapter(source)

    private fun key(zoom: Int, x: Int, y: Int): FogTileKey =
        FogTileKey(zoom = zoom, x = x, y = y, renderVersion = FogRenderVersions.CURRENT)

    private fun decode(bytes: ByteArray) =
        requireNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "expected a PNG tile" }

    private fun alphaAt(image: java.awt.image.BufferedImage, x: Int, y: Int): Int =
        image.getRGB(x, y).ushr(24) and 0xff
}
