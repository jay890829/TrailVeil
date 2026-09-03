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
}
