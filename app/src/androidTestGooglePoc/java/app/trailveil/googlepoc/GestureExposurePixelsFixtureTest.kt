package app.trailveil.googlepoc

import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GestureExposurePixelsFixtureTest {
    private val fog = Color.rgb(31, 38, 43)

    @Test fun memoizedTallyMatchesFrozenReferenceAcrossPixelsRegionsAndExclusions() {
        val random = Random(20260912)
        for ((width, height) in listOf(32 to 32, 37 to 29, 128 to 96)) {
            val bitmap = createBitmap(width, height)
            try {
                val patterns = listOf(
                    IntArray(width * height), // ARGB 0 must not act as an uninitialised memo hit.
                    IntArray(width * height) { Color.WHITE },
                    IntArray(width * height) { fog },
                    IntArray(width * height) { index -> if (index % 3 == 0) Color.WHITE else fog },
                    IntArray(width * height) { index -> (index % 256 shl 24) or (fog and 0x00ffffff) },
                    IntArray(width * height) { random.nextInt() },
                    IntArray(width * height) { index -> if ((index / width / 4 + index % width / 4) % 2 == 0) Color.WHITE else fog },
                )
                for (pixels in patterns) {
                    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    for (region in listOf(Rect(0, 0, width, height), Rect(3, 5, width - 2, height - 1),
                        Rect(-3, -2, width + 2, height + 4), Rect(0, 0, 3, 3))) {
                        for (excluded in listOf(emptyList(), listOf(Rect(4, 0, 8, height)),
                            listOf(Rect(0, 0, width / 2, height / 2), Rect(4, 4, width - 3, height - 5)))) {
                            val expected = GestureExposurePixelsB45Reference.tally(bitmap, region, excluded)
                            val actual = GestureExposurePixels.tally(bitmap, region, excluded)
                            assertEquals(listOf(expected.analyzedPx, expected.excludedPx, expected.exposedPx, expected.largestClusterPx),
                                listOf(actual.analyzedPx, actual.excludedPx, actual.exposedPx, actual.largestClusterPx))
                        }
                    }
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun barePixelsAndEightConnectedClustersCannotDisappearOnMemoHits() {
        val width = 64; val height = 64
        val bitmap = createBitmap(width, height)
        val region = Rect(0, 0, width, height)
        try {
            bitmap.eraseColor(fog)
            assertEquals(0, GestureExposurePixels.tally(bitmap, region, emptyList()).exposedPx)
            for (bare in listOf(Color.WHITE, Color.TRANSPARENT)) {
                bitmap.eraseColor(bare)
                val result = GestureExposurePixels.tally(bitmap, region, emptyList())
                assertEquals(width * height, result.exposedPx)
                assertEquals(width * height, result.largestClusterPx)
                assertEquals(0, GestureExposurePixels.tally(bitmap, region, listOf(region)).analyzedPx)
            }
            // Two diagonal sampled points form one 8-connected cluster; a distant one stays apart.
            val pixels = IntArray(width * height) { fog }
            pixels[4 * width + 4] = Color.WHITE
            pixels[8 * width + 8] = Color.WHITE
            pixels[48 * width + 48] = Color.WHITE
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            val result = GestureExposurePixels.tally(bitmap, region, emptyList())
            assertEquals(48, result.exposedPx)
            assertEquals(32, result.largestClusterPx)
            val excluded = GestureExposurePixels.tally(bitmap, region, listOf(Rect(8, 8, 9, 9)))
            assertEquals(32, excluded.exposedPx)
            assertEquals(16, excluded.largestClusterPx)
            // No memo state may cross from a bare frame into this new fog frame.
            bitmap.eraseColor(fog)
            assertEquals(0, GestureExposurePixels.tally(bitmap, region, emptyList()).exposedPx)
        } finally { bitmap.recycle() }
    }
}
