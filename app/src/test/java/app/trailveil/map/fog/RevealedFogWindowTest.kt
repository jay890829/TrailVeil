package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-012` design 2: the revealed-fog window the Google verification and the device audits read.
 * A revealed overlay is displayed at `FogRenderStyle.fogAlpha` (184/255), so a fog pixel is the
 * generation colour blended over the basemap; bare basemap of the assumed lightness lies far above
 * the window, and labels drawn above the overlay lie below it.
 */
class RevealedFogWindowTest {
    private val alpha = FogTilePngCodec.REVEALED_FOG_ALPHA / 255.0

    @Test
    fun `the union window's real boundary is where bare basemap starts to be rejected`() {
        // Enumerated over the whole palette: these four numbers ARE the predicate's reach. If a
        // change moves them, the KDoc in FogTilePngCodec and the evidence must move with it.
        val greysThatMatch = (0..255).filter { value ->
            FogTilePngCodec.matchesRevealedFogAnyGeneration(FogTileColor(value, value, value))
        }
        assertEquals("the accepted bare-grey band, inclusive", 56, greysThatMatch.first())
        assertEquals(104, greysThatMatch.last())
        // Just outside it, on each side, nothing matches.
        assertFalse(FogTilePngCodec.matchesRevealedFogAnyGeneration(FogTileColor(55, 55, 55)))
        assertFalse(FogTilePngCodec.matchesRevealedFogAnyGeneration(FogTileColor(105, 105, 105)))
        // A bare pixel escapes on ANY channel, which is what makes real basemap safe: Google's
        // light-style land, roads, buildings, parks and water are all far above on at least one.
        listOf(
            FogTileColor(245, 243, 238),
            FogTileColor(255, 255, 255),
            FogTileColor(237, 237, 237),
            FogTileColor(200, 230, 200),
            FogTileColor(114, 223, 255),
        ).forEach { bare ->
            assertFalse("bare $bare", FogTilePngCodec.matchesRevealedFogAnyGeneration(bare))
        }
        // And the case the old test never covered: mid-grey label text DOES satisfy the window.
        // Recorded, not asserted away - the block and tile quorums are what reject a bare frame.
        assertTrue(FogTilePngCodec.matchesRevealedFogAnyGeneration(FogTileColor(97, 97, 97)))
    }

    private fun blend(fog: Int, basemap: Int): Int = Math.round(alpha * fog + (1 - alpha) * basemap).toInt()

    @Test
    fun theWindowIsTheFogBlendedOverTheAssumedBasemapRange() {
        assertEquals(184, FogTilePngCodec.REVEALED_FOG_ALPHA)
        val fog = FogTilePngCodec.DEFAULT_FOG_COLOR.red
        val range = FogTilePngCodec.revealedFogChannelRange(fog)
        assertEquals(blend(fog, FogTilePngCodec.BASEMAP_MINIMUM_CHANNEL) - FogTilePngCodec.REVEALED_FOG_TOLERANCE, range.first)
        assertEquals(blend(fog, 255) + FogTilePngCodec.REVEALED_FOG_TOLERANCE, range.last)
        // Concretely, for the default red channel: fog 31 over a basemap of 96..255 reads 49..93.
        assertTrue(range.first in 44..51)
        assertTrue(range.last in 91..97)
        // Google's light-style water reads about (114, 223, 255): fog over it must be fog, and
        // bare water (red above the window's top) must not.
        val water = FogTileColor(114, 223, 255)
        assertTrue(FogTilePngCodec.matchesRevealedFog(FogTileColor(blend(fog, water.red), blend(FogTilePngCodec.DEFAULT_FOG_COLOR.green, water.green), blend(FogTilePngCodec.DEFAULT_FOG_COLOR.blue, water.blue)), 1L))
        assertFalse(FogTilePngCodec.matchesRevealedFog(water, 1L))
        assertFalse(FogTilePngCodec.matchesRevealedFogAnyGeneration(water))
    }

    @Test
    fun fogOverALightBasemapMatchesAndBareBasemapDoesNot() {
        val generation = 5L
        val fog = FogTilePngCodec.colorForGeneration(generation)
        listOf(255, 240, 200, 160, 120, 100).forEach { basemap ->
            val pixel = FogTileColor(blend(fog.red, basemap), blend(fog.green, basemap), blend(fog.blue, basemap))
            assertTrue("fog over a $basemap basemap is fog", FogTilePngCodec.matchesRevealedFog(pixel, generation))
            assertTrue(FogTilePngCodec.matchesRevealedFogAnyGeneration(pixel))
        }
        // The brightest palette colour tops the window at 104, so bare basemap is proven bare
        // from 105 up (water, the darkest fill, reads about 114 in red).
        listOf(255, 240, 200, 160, 120, 110).forEach { basemap ->
            val bare = FogTileColor(basemap, basemap, basemap)
            assertFalse("bare basemap at $basemap is not fog", FogTilePngCodec.matchesRevealedFog(bare, generation))
            assertFalse(FogTilePngCodec.matchesRevealedFogAnyGeneration(bare))
        }
    }

    @Test
    fun labelsDrawnAboveTheOverlayAreNotFog() {
        // Black text, a white label plate and the tile's own opaque colour (an unrevealed overlay).
        listOf(FogTileColor(0, 0, 0), FogTileColor(255, 255, 255), FogTilePngCodec.colorForGeneration(1L)).forEach { pixel ->
            assertFalse(FogTilePngCodec.matchesRevealedFog(pixel, 1L))
            assertFalse(FogTilePngCodec.matchesRevealedFogAnyGeneration(pixel))
        }
    }

    @Test
    fun beneathTheCoverEverythingFromALabelToWhiteMatchesAndNothingBareDoes() {
        val cover = FogTilePngCodec.DEFAULT_FOG_COLOR
        fun covered(under: FogTileColor) = FogTileColor(blend(cover.red, under.red), blend(cover.green, under.green), blend(cover.blue, under.blue))
        // The cover over the basemap (overlays hidden), over a revealed generation (the hold) and
        // over a black label all read as covered.
        listOf(120, 200, 255).forEach { basemap ->
            assertTrue("cover over basemap $basemap", FogTilePngCodec.matchesBeneathCover(covered(FogTileColor(basemap, basemap, basemap))))
            for (generation in 1L..FogTilePngCodec.SIGNATURE_COLOUR_COUNT) {
                val fog = FogTilePngCodec.colorForGeneration(generation)
                val revealed = FogTileColor(blend(fog.red, basemap), blend(fog.green, basemap), blend(fog.blue, basemap))
                assertTrue("cover over generation $generation over $basemap", FogTilePngCodec.matchesBeneathCover(covered(revealed)))
                assertTrue(FogTilePngCodec.matchesBeneathCover(covered(FogTileColor(0, 0, 0))))
            }
        }
        // Nothing bare does: basemap of the assumed lightness, white, and a black label.
        listOf(100, 120, 160, 200, 255).forEach { basemap ->
            assertFalse("bare $basemap", FogTilePngCodec.matchesBeneathCover(FogTileColor(basemap, basemap, basemap)))
        }
        assertFalse(FogTilePngCodec.matchesBeneathCover(FogTileColor(0, 0, 0)))
        assertFalse(FogTilePngCodec.matchesBeneathCover(FogTileColor(255, 255, 255)))
    }

    @Test
    fun theAnyGenerationWindowCoversEveryPaletteColour() {
        for (generation in 1L..FogTilePngCodec.SIGNATURE_COLOUR_COUNT) {
            val fog = FogTilePngCodec.colorForGeneration(generation)
            listOf(120, 255).forEach { basemap ->
                val pixel = FogTileColor(blend(fog.red, basemap), blend(fog.green, basemap), blend(fog.blue, basemap))
                assertTrue("generation $generation over $basemap", FogTilePngCodec.matchesRevealedFogAnyGeneration(pixel))
            }
        }
    }
}
