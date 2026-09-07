package app.trailveil.map.fog

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
     * `V03-011` section 15e: the scaled form is the one arm 2 actually installs, and its first
     * obligation is to be the unscaled one when nothing is scaled.
     */
    @Test
    fun `asking for the mask's own size is the unscaled rule exactly`() {
        val mask = checkerMask(width = 4, height = 6)
        val color = FogTileColor(red = 31, green = 38, blue = 43)

        assertArrayEquals(
            "a no-op scale must not take a different path from toArgb, or the two rules drift",
            FogMaskArgb.toArgb(mask, color),
            FogMaskArgb.toArgbScaled(mask, color, targetWidth = 4, targetHeight = 6),
        )
    }

    /**
     * Fog has two states per pixel and scaling must not invent a third.
     *
     * Nearest-neighbour is not a performance choice here: an interpolating resample would emit
     * pixels between transparent and the generation's signature colour, which is ground that no
     * mask contains AND a colour [FogTilePngCodec.matchesRevealedFog] cannot recognise - so it
     * would quietly make every frame unprovable. This is the same decision MapLibre's shipped
     * `rasterResampling(NEAREST)` makes on the other provider.
     */
    @Test
    fun `scaling never invents a pixel that is neither explored nor fog`() {
        val mask = checkerMask(width = 7, height = 5)
        val color = FogTileColor(red = 31, green = 38, blue = 43)
        val opaque = (0xff shl 24) or (31 shl 16) or (38 shl 8) or 43

        listOf(3 to 2, 14 to 11, 7 to 20, 1 to 1).forEach { (width, height) ->
            val pixels = FogMaskArgb.toArgbScaled(mask, color, width, height)
            assertEquals("${width}x$height", width * height, pixels.size)
            pixels.forEach { pixel ->
                assertTrue(
                    "a resample produced $pixel at ${width}x$height, which is neither fully " +
                        "transparent nor the generation's signature colour",
                    pixel == 0 || pixel == opaque,
                )
            }
        }
    }

    /**
     * Which source pixel each output pixel takes, stated as a case rather than left to the reader.
     *
     * Centre sampling: output pixel `x` of `n` reads source column `(2x+1)*w / 2n`. On a 4-wide
     * mask halved that is columns 0 and 2 - not 0 and 1, which corner sampling would give and which
     * biases the whole image towards the mask's top-left by half an output pixel. At a tilted pose
     * half an output pixel is ground.
     */
    @Test
    fun `halving takes the centre of each output pixel, not its corner`() {
        // One row, fog only in columns 2 and 3: 0 0 1 1
        val mask = FogPixelMask(
            width = 4,
            height = 1,
            alpha = byteArrayOf(0, 0, 255.toByte(), 255.toByte()),
        )
        val color = FogTileColor(red = 1, green = 2, blue = 3)
        val opaque = (0xff shl 24) or (1 shl 16) or (2 shl 8) or 3

        val halved = FogMaskArgb.toArgbScaled(mask, color, targetWidth = 2, targetHeight = 1)

        assertArrayEquals(
            "columns 0 and 2 are the centres of the two output pixels; corner sampling would read " +
                "columns 0 and 1 and report the row as entirely explored",
            intArrayOf(0, opaque),
            halved,
        )
    }

    /** Upsampling is the same rule read the other way: every output pixel finds its source. */
    @Test
    fun `upsampling repeats source pixels instead of blending them`() {
        val mask = FogPixelMask(width = 2, height = 1, alpha = byteArrayOf(0, 255.toByte()))
        val color = FogTileColor(red = 9, green = 9, blue = 9)
        val opaque = (0xff shl 24) or (9 shl 16) or (9 shl 8) or 9

        val tripled = FogMaskArgb.toArgbScaled(mask, color, targetWidth = 6, targetHeight = 2)

        assertArrayEquals(
            intArrayOf(0, 0, 0, opaque, opaque, opaque, 0, 0, 0, opaque, opaque, opaque),
            tripled,
        )
    }

    /**
     * The signature has to survive the scale, or the snapshot proof stops recognising the fog.
     *
     * `toArgbScaledForGeneration` is what arm 2 calls; if it lost the generation colour the proof
     * would fail closed - the cover would never come down - which is safe and completely
     * undiagnosable from a device.
     */
    @Test
    fun `the generation signature travels through a scale`() {
        val mask = checkerMask(width = 5, height = 3)
        val generation = 7L

        val scaled = FogMaskArgb.toArgbScaledForGeneration(
            mask = mask,
            generation = generation,
            targetWidth = 10,
            targetHeight = 6,
        )

        val expected = FogTilePngCodec.colorForGeneration(generation)
        val opaque = (0xff shl 24) or
            (expected.red shl 16) or
            (expected.green shl 8) or
            expected.blue
        assertTrue(
            "no fog pixel carried the generation's colour, so the proof would not recognise it",
            scaled.any { pixel -> pixel == opaque },
        )
        scaled.forEach { pixel ->
            assertTrue("stray colour $pixel", pixel == 0 || pixel == opaque)
        }
    }

    @Test
    fun `a non-positive target is refused rather than returning an empty image`() {
        val mask = checkerMask(width = 2, height = 2)
        val color = FogTileColor(red = 0, green = 0, blue = 0)
        listOf(0 to 4, 4 to 0, -1 to 4).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) {
                FogMaskArgb.toArgbScaled(mask, color, width, height)
            }
        }
    }

    /** Alternating fog and explored in both axes, so a resample that drifts shows up as a change. */
    private fun checkerMask(width: Int, height: Int): FogPixelMask = FogPixelMask(
        width = width,
        height = height,
        alpha = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if ((x + y) % 2 == 0) 0 else 255.toByte()
        },
    )

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
        // Both dimensions are named, because the ratio below is only 6.07 and a comparison
        // against a bare 6 with a 1% margin would break on a 1080x2436 device with nothing
        // about prototype A changed. What the case means is "several times", so it says so.
        val screenBytes = FogMaskArgb.byteCost(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        assertEquals(10_368_000L, screenBytes)
        assertTrue(
            "the tile-resolution mosaic must be several times the viewport-resolution one, " +
                "or the design decision this case exists to justify is stale: " +
                "$bytes against $screenBytes",
            bytes > screenBytes * 4,
        )
    }

    private companion object {
        /** The measured AVD and the designated phone share these; see section 14b. */
        const val VIEWPORT_WIDTH = 1080
        const val VIEWPORT_HEIGHT = 2400
    }
}
