package app.trailveil.map.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V03-013`: the vector arms' geometry, checked where it is cheap to check.
 *
 * The property that matters is not prettiness, it is that the rectangles never overlap. Overlapping
 * holes are not holes - a ring inside another ring can re-fill what it was meant to remove - so a
 * decomposition that overlapped would reveal or re-fog ground at random depending on the SDK's fill
 * rule. That is asserted directly rather than inferred from the algorithm's shape.
 */
class FogMaskContoursTest {

    private fun mask(width: Int, height: Int, revealed: (Int, Int) -> Boolean): FogPixelMask {
        val alpha = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                alpha[y * width + x] = if (revealed(x, y)) 0 else 255.toByte()
            }
        }
        return FogPixelMask(width, height, alpha)
    }

    @Test
    fun `a fully fogged mask decomposes to nothing`() {
        val result = FogMaskContours.decompose(mask(16, 16) { _, _ -> false })
        assertTrue(result.rects.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun `a fully revealed mask decomposes to one rectangle`() {
        val result = FogMaskContours.decompose(mask(16, 12) { _, _ -> true })
        assertEquals(listOf(FogMaskRect(0, 0, 16, 12)), result.rects)
    }

    @Test
    fun `a revealed block becomes one rectangle covering exactly it`() {
        val result = FogMaskContours.decompose(
            mask(16, 16) { x, y -> x in 4..7 && y in 2..9 },
        )
        assertEquals(listOf(FogMaskRect(4, 2, 8, 10)), result.rects)
    }

    @Test
    fun `two blocks on the same rows stay two rectangles`() {
        val result = FogMaskContours.decompose(
            mask(16, 4) { x, _ -> x in 1..2 || x in 10..12 },
        )
        assertEquals(
            listOf(FogMaskRect(1, 0, 3, 4), FogMaskRect(10, 0, 13, 4)),
            result.rects.sortedBy { it.left },
        )
    }

    /** The safety property. A staircase is the shape most likely to expose an overlap bug. */
    @Test
    fun `rectangles never overlap, even for a diagonal corridor`() {
        val result = FogMaskContours.decompose(
            mask(64, 64) { x, y -> kotlin.math.abs(x - y) <= 3 },
        )
        assertTrue("a diagonal corridor should produce many rectangles", result.rects.size > 10)
        val seen = HashSet<Long>()
        result.rects.forEach { rect ->
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    assertTrue(
                        "pixel ($x, $y) is covered by more than one rectangle",
                        seen.add(y.toLong() * 1024L + x.toLong()),
                    )
                }
            }
        }
    }

    /** Every revealed pixel is covered, so the decomposition cannot leave fog on revealed ground. */
    @Test
    fun `every revealed pixel is covered exactly once`() {
        val revealed = { x: Int, y: Int -> (x - 20) * (x - 20) + (y - 20) * (y - 20) <= 100 }
        val result = FogMaskContours.decompose(mask(48, 48, revealed))
        val covered = HashSet<Long>()
        result.rects.forEach { rect ->
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) covered.add(y.toLong() * 1024L + x.toLong())
            }
        }
        for (y in 0 until 48) {
            for (x in 0 until 48) {
                assertEquals(
                    "pixel ($x, $y)",
                    revealed(x, y),
                    covered.contains(y.toLong() * 1024L + x.toLong()),
                )
            }
        }
    }

    /**
     * A coarser step must SHRINK the revealed region, never grow it.
     *
     * Growing it would show ground the raster fogs, which is the one error this surface cannot
     * make. A cell counts as revealed only when every pixel under it is, so the coarse result is a
     * subset of the fine one.
     */
    @Test
    fun `a coarser step reveals a subset of what the finest step reveals`() {
        val target = mask(64, 64) { x, y -> (x - 30) * (x - 30) + (y - 30) * (y - 30) <= 225 }
        fun covered(step: Int): Set<Long> {
            val out = HashSet<Long>()
            FogMaskContours.decompose(target, step = step).rects.forEach { rect ->
                for (y in rect.top until rect.bottom) {
                    for (x in rect.left until rect.right) out.add(y.toLong() * 1024L + x.toLong())
                }
            }
            return out
        }
        val fine = covered(1)
        val coarse = covered(4)
        assertTrue("a coarse step must not reveal anything the fine step does not", fine.containsAll(coarse))
    }

    @Test
    fun `truncation is reported rather than silently dropping geometry`() {
        val result = FogMaskContours.decompose(
            mask(64, 64) { x, y -> kotlin.math.abs(x - y) <= 1 },
            maxRects = 4,
        )
        assertTrue("the budget should have been reached", result.truncated)
        assertTrue("no more than the budget is emitted", result.rects.size <= 4)
    }
}
