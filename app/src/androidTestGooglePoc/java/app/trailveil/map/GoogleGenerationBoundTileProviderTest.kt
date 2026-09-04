package app.trailveil.map

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.get
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.trailveil.googlepoc.GoogleFogTileProvider
import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogRenderStyle
import app.trailveil.map.fog.FogRenderVersions
import app.trailveil.map.fog.FogTileColor
import app.trailveil.map.fog.FogTileKey
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.FogTileProviderAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleGenerationBoundTileProviderTest {
    @Test
    fun oldAndBootstrapProvidersCanNeverServeTheNewGenerationSignature() {
        // The native old overlay may still cache generation-1 pixels when generation 64 reuses
        // its colour; coordinator ordering tests therefore remove old before the G64 snapshot.
        assertTrue(
            FogTilePngCodec.colorForGeneration(1) == FogTilePngCodec.colorForGeneration(64),
        )
        val adapter = FogTileProviderAdapter()
        val key = FogTileKey(4, 8, 8, FogRenderVersions.CURRENT)
        val first = adapter.beginGeneration()
        assertTrue(adapter.publishMasks(first, mapOf(key to opaqueMask())))
        val old = GoogleFogTileProvider(adapter, acceptedGeneration = first.id)
        val bootstrap = GoogleFogTileProvider(adapter, acceptedGeneration = Long.MIN_VALUE)
        assertTrue(matches(tilePixel(old), first.id))

        val second = adapter.beginHandoverGeneration()
        assertTrue(adapter.publishMasks(second, mapOf(key to opaqueMask())))
        val target = GoogleFogTileProvider(adapter, acceptedGeneration = second.id)

        assertFalse(matches(tilePixel(old), second.id))
        assertFalse(matches(tilePixel(bootstrap), second.id))
        assertTrue(matches(tilePixel(target), second.id))
    }

    /**
     * `V02-007`, condition (a) of the inventory's NA argument.
     *
     * On Google the placeholder is the whole of the fail-closed guarantee: every mechanism-level
     * MapLibre case is discharged by "the provider answers every key the SDK renders, opaquely",
     * so what it answers for a key this generation never published is load-bearing. The shared
     * `FogTileProviderAdapterTest` asserts that tile is fully OPAQUE, provider-neutrally, and the
     * case above only ever asks this provider for keys it has just published - so nothing yet
     * asserted the COLOUR a generation-bound Google provider hands the SDK for an unpublished or
     * unrepresentable key. An opaque-but-wrong placeholder passes every existing assertion and
     * reaches the user as a flat foreign patch where fog should be.
     */
    @Test
    fun unpublishedAndOutOfWindowKeysAnswerFogFamilyPixelsAtFullAlpha() {
        val adapter = FogTileProviderAdapter()
        val published = FogTileKey(ZOOM, TILE_X, TILE_Y, FogRenderVersions.CURRENT)
        val generation = adapter.beginGeneration()
        assertTrue(adapter.publishMasks(generation, mapOf(published to opaqueMask())))
        val provider = GoogleFogTileProvider(adapter, acceptedGeneration = generation.id)
        // Anti-vacuity: this provider really can serve canonical bytes for this generation, so the
        // two answers below are the fail-closed path and not an adapter that published nothing.
        assertTrue(matches(tilePixel(provider), generation.id))

        val neverPublished = uniformTilePixel(provider, x = TILE_X + 1, y = TILE_Y, zoom = ZOOM)
        // y == 2^zoom is off the vertical Web Mercator window. The adapter refuses to normalise it
        // (only x wraps), so this key never reaches the canonical source at all - a second, wholly
        // separate route to the placeholder.
        val outOfWindow = uniformTilePixel(provider, x = TILE_X, y = 1 shl ZOOM, zoom = ZOOM)

        listOf(
            "never-published key" to neverPublished,
            "out-of-window key" to outOfWindow,
        ).forEach { (name, pixel) ->
            assertEquals("$name was not fully opaque", 255, Color.alpha(pixel))
            val colour = FogTileColor(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            assertEquals(
                "$name was not the fog placeholder colour",
                FogTilePngCodec.DEFAULT_FOG_COLOR,
                colour,
            )
            assertTrue("$name left the fog colour family: $colour", isFogFamily(colour))
            // ...and it still cannot be read as proof: the placeholder is outside every
            // generation's signature window, so no snapshot oracle can accept it for one.
            assertFalse(
                "$name was mistakable for the published generation's signature",
                FogTilePngCodec.matchesGenerationColor(colour, generation.id),
            )
        }
    }

    /** The colour of a tile that must be one flat colour; fails when any pixel differs. */
    private fun uniformTilePixel(
        provider: GoogleFogTileProvider,
        x: Int,
        y: Int,
        zoom: Int,
    ): Int {
        val tile = provider.getTile(x, y, zoom)
        val data = requireNotNull(tile.data) { "the provider answered with no tile bytes" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
        return try {
            assertEquals(FogTilePngCodec.TILE_SIZE, bitmap.width)
            assertEquals(FogTilePngCodec.TILE_SIZE, bitmap.height)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            // Only the count: a failing tile can hold tens of thousands of distinct colours, and
            // this message is built on every call.
            val distinctColours = pixels.toHashSet().size
            assertEquals("the tile was not one flat colour", 1, distinctColours)
            pixels[0]
        } finally {
            bitmap.recycle()
        }
    }

    /** The palette space every fog tile lives in: the base colour plus three signature steps. */
    private fun isFogFamily(colour: FogTileColor): Boolean {
        val base = FogTilePngCodec.DEFAULT_FOG_COLOR
        val span = 0..(FogTilePngCodec.SIGNATURE_CHANNEL_STEP * SIGNATURE_STEPS_PER_CHANNEL)
        return colour.red - base.red in span &&
            colour.green - base.green in span &&
            colour.blue - base.blue in span
    }

    private fun tilePixel(provider: GoogleFogTileProvider): Int {
        val tile = provider.getTile(8, 8, 4)
        val data = requireNotNull(tile.data)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
        return try {
            bitmap[128, 128]
        } finally {
            bitmap.recycle()
        }
    }

    private fun matches(pixel: Int, generation: Long): Boolean =
        FogTilePngCodec.matchesGenerationColor(
            FogTileColor(Color.red(pixel), Color.green(pixel), Color.blue(pixel)),
            generation,
        )

    private fun opaqueMask() = FogPixelMask(
        FogTilePngCodec.TILE_SIZE,
        FogTilePngCodec.TILE_SIZE,
        ByteArray(FogTilePngCodec.TILE_SIZE * FogTilePngCodec.TILE_SIZE) {
            FogRenderStyle().fogAlpha.toByte()
        },
    )

    private companion object {
        const val ZOOM = 4
        const val TILE_X = 8
        const val TILE_Y = 8

        /** Each channel of the signature palette carries a base-4 digit: three steps above base. */
        const val SIGNATURE_STEPS_PER_CHANNEL = 3
    }
}
