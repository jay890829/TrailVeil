package app.trailveil.map

import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileMosaic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class MapLibreRasterPreparationTest {
    @Test fun everyAlphaAndRowMajorPatternMatchesTheFrozenConversion() = runTest {
        for ((width, height) in listOf(256 to 1, 13 to 23, 129 to 67)) {
            val alpha = ByteArray(width * height) { (it * 73 + it / width).toByte() }
            val mosaic = mosaic(width, height, alpha)
            val before = alpha.copyOf()
            val prepared = prepareRasterFog(mosaic)
            assertSame(mosaic, prepared.mosaic)
            assertArrayEquals(frozenPixels(before), pixels(prepared))
            assertArrayEquals(before, mosaic.mask.copyAlpha())
            // Source constructors rely on ownership; even a deliberate alias mutation cannot
            // change an already prepared snapshot.
            alpha.fill(0)
            assertArrayEquals(frozenPixels(before), pixels(prepared))
        }
    }

    @Test fun bulkPackingWritesOnlyTheRequestedRange() {
        val mask = FogPixelMask(9, 1, byteArrayOf(0, 1, 2, 127, -128, -1, 17, 29, 31))
        val destination = IntArray(9) { 0x12345678 }
        mask.packArgbInto(destination, 2, 7, MAPLIBRE_FOG_COLOR)
        assertArrayEquals(intArrayOf(0x12345678, 0x12345678), destination.copyOfRange(0, 2))
        assertArrayEquals(frozenPixels(mask.copyAlpha()).copyOfRange(2, 7), destination.copyOfRange(2, 7))
        assertArrayEquals(intArrayOf(0x12345678, 0x12345678), destination.copyOfRange(7, 9))
    }

    @Test fun equalButDistinctMosaicCannotConsumeAnotherPayload() = runTest {
        val original = mosaic(2, 3, ByteArray(6) { 123 })
        val equal = original.copy()
        assertEquals(original, equal)
        val prepared = prepareRasterFog(original)
        try {
            prepared.createBitmapFor(equal)
            fail("equal value is not the same generation payload")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("different mosaic"))
        }
    }

    @Test fun cancellationAtAnyChunkIncludingTheFinalOnePublishesNoPayload() = runTest {
        val prior = RasterFogPreparationTestProbe.onChunk
        try {
            for (cancelAt in listOf(1, 3, 4)) {
                val job = Job()
                var chunks = 0
                var returned = false
                RasterFogPreparationTestProbe.onChunk = { _, _, actualJob ->
                    chunks++
                    assertNotNull(actualJob)
                    if (chunks == cancelAt) job.cancel()
                }
                try {
                    withContext(job) {
                        prepareRasterFog(mosaic(128, 128, ByteArray(16_384) { 184.toByte() }))
                        returned = true
                    }
                    fail("expected cooperative cancellation")
                } catch (_: CancellationException) { }
                assertFalse(returned)
                assertEquals("no later chunk may be packed after cancellation", cancelAt, chunks)
            }
            var entered = false
            RasterFogPreparationTestProbe.onChunk = { _, _, _ -> entered = true }
            try {
                withContext(Job().apply { cancel() }) { prepareRasterFog(mosaic(1, 1, byteArrayOf(1))) }
                fail("pre-cancelled job must not prepare")
            } catch (_: CancellationException) { }
            assertFalse(entered)
        } finally {
            RasterFogPreparationTestProbe.onChunk = prior
        }
    }

    @Test fun malformedDimensionsAndMaskLengthFailClosed() = runTest {
        for (bad in listOf(mosaic(2, 2, ByteArray(3)), mosaic(2, 2, ByteArray(5)), mosaic(0, 1, ByteArray(0)))) {
            try { prepareRasterFog(bad); fail("invalid mask shape") }
            catch (_: IllegalArgumentException) { }
        }
    }

    // Frozen b48 toBitmap packing, independently retained rather than invoking the new bulk API.
    private fun frozenPixels(alpha: ByteArray): IntArray = IntArray(alpha.size) { index ->
        val unsigned = alpha[index].toInt() and 0xff
        if (unsigned == 0) 0 else (unsigned shl 24) or 0x001F262B
    }

    private fun pixels(prepared: PreparedRasterFog): IntArray =
        (PreparedRasterFog::class.java.getDeclaredField("pixels").apply { isAccessible = true }.get(prepared) as IntArray).copyOf()

    private fun mosaic(width: Int, height: Int, alpha: ByteArray) =
        FogTileMosaic(FogPixelMask(width, height, alpha), FogTileBounds(-1.0, -1.0, 1.0, 1.0), 1)
}
