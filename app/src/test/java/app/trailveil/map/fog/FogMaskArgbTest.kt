package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V03-011` prototype A's rasteriser, and the number that decides whether prototype A is
 * affordable at the resolution it is easiest to build at.
 */
class FogMaskArgbTest {

    @Test
    fun `alpha zero is explored and every other value is opaque fog`() {
        val mask = FogPixelMask(
            width = 2,
            height = 2,
            alpha = byteArrayOf(0, 1, 184.toByte(), 255.toByte()),
        )
        val color = FogTileColor(red = 31, green = 38, blue = 43)

        val pixels = FogMaskArgb.toArgb(mask, color)

        assertEquals(4, pixels.size)
        assertEquals("mask alpha 0 is explored ground and must be fully transparent", 0, pixels[0])
        val opaque = (0xff shl 24) or (31 shl 16) or (38 shl 8) or 43
        listOf(1, 2, 3).forEach { index ->
            assertEquals(
                "every non-zero mask value is fog and is fully opaque; the fog's translucency " +
                    "comes from the overlay's display alpha, never from these pixels",
                opaque,
                pixels[index],
            )
        }
    }

    /**
     * The converter must agree with [FogTilePngCodec.encode], because a prototype that rasterises
     * the same mask differently from the shipped tile path would move the fog boundary while
     * appearing to change only the object type.
     */
    @Test
    fun `the rule is the encoder's rule, checked against the encoder's own contract`() {
        val alpha = ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE) { index ->
            if (index % 3 == 0) 0 else 184.toByte()
        }
        val mask = FogPixelMask(
            width = FogTilePngCodec.TILE_SIZE,
            height = FogTilePngCodec.TILE_SIZE,
            alpha = alpha,
        )
        val color = FogTilePngCodec.colorForGeneration(7L)

        val pixels = FogMaskArgb.toArgb(mask, color)

        // The encoder writes colour in every pixel and varies only the alpha byte, so the two
        // agree iff transparency agrees pixel for pixel and the colour is the generation's.
        var checked = 0
        for (y in 0 until FogTilePngCodec.TILE_SIZE) {
            for (x in 0 until FogTilePngCodec.TILE_SIZE) {
                val argb = pixels[y * FogTilePngCodec.TILE_SIZE + x]
                val expectedOpaque = mask.alphaAt(x, y) != 0
                assertEquals(
                    "transparency disagrees at ($x, $y)",
                    expectedOpaque,
                    (argb ushr 24) == 0xff,
                )
                if (expectedOpaque) {
                    assertEquals(color.red, (argb shr 16) and 0xff)
                    assertEquals(color.green, (argb shr 8) and 0xff)
                    assertEquals(color.blue, argb and 0xff)
                }
                checked += 1
            }
        }
        assertEquals(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE, checked)
    }

    @Test
    fun `the signature travels, so the existing snapshot proof still recognises the fog`() {
        val mask = FogPixelMask(width = 1, height = 1, alpha = byteArrayOf(184.toByte()))

        val first = FogMaskArgb.toArgbForGeneration(mask, 1L)
        val second = FogMaskArgb.toArgbForGeneration(mask, 2L)

        assertEquals(
            "the rasterised colour must be the generation's signature, not the default fog",
            FogTilePngCodec.colorForGeneration(1L),
            FogTileColor(
                red = (first[0] shr 16) and 0xff,
                green = (first[0] shr 8) and 0xff,
                blue = first[0] and 0xff,
            ),
        )
        assertTrue(
            "consecutive generations must be distinguishable, or the proof cannot tell a stale " +
                "overlay from a fresh one",
            first[0] != second[0],
        )
    }

    /**
     * Prototype A's affordability, as arithmetic rather than as a device measurement.
     *
     * A `TileOverlay` holds the viewport as separate tiles the SDK pages in and out. A single
     * anchored image is ONE allocation, and composing it from the tile mosaic means composing it at
     * the tiles' own resolution: the survey measured the rectangular completion at 240 tiles, which
     * is 15x16 at 256 pixels each.
     *
     * The figure this produces is the reason prototype A cannot simply be "compose the mosaic and
     * hand it over". It is not a reason prototype A is wrong - a viewport-resolution raster is a
     * fraction of it - but that needs a different rasteriser, projecting the canonical mask to
     * screen rather than assembling tiles, and that is a design decision the spike must take
     * explicitly rather than discover on a device as an OOM.
     */
    @Test
    fun `the tile-resolution mosaic costs tens of megabytes in one allocation`() {
        val tile = FogTilePngCodec.TILE_SIZE
        val mosaicWidth = 15 * tile
        val mosaicHeight = 16 * tile

        val bytes = FogMaskArgb.byteCost(mosaicWidth, mosaicHeight)

        assertEquals(3840, mosaicWidth)
        assertEquals(4096, mosaicHeight)
        assertEquals(62_914_560L, bytes)

        // A viewport-resolution raster, for the comparison the design decision turns on.
        val screenBytes = FogMaskArgb.byteCost(1080, 2400)
        assertEquals(10_368_000L, screenBytes)
        assertTrue(
            "the tile-resolution mosaic must be the expensive option, or this test is stale",
            bytes > screenBytes * 6,
        )
    }
}
