package app.trailveil.map.fog

/**
 * A revealed region of a mask, as an axis-aligned rectangle in pixel coordinates.
 *
 * Half-open on both axes: `[left, right)` by `[top, bottom)`.
 */
data class FogMaskRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left) { "right must be greater than left" }
        require(bottom > top) { "bottom must be greater than top" }
    }
}

/** What a decomposition produced, and what it had to give up to stay inside its budget. */
data class FogMaskContourResult(
    val rects: List<FogMaskRect>,
    /** True when [FogMaskContours.MAX_RECTS] stopped the walk before the mask was finished. */
    val truncated: Boolean,
)

/**
 * Turns a mask's revealed pixels into non-overlapping rectangles.
 *
 * `V03-013`: the vector arms need holes, and holes that overlap are not holes - a ring inside
 * another ring can re-fill the region it was meant to remove, and neither SDK promises which. The
 * union that would make overlapping reveal circles safe is exactly the dependency the owner
 * deferred, so this takes the union that has ALREADY happened: the renderer rasterised those
 * overlapping circles into one connected region when it drew the mask, and a decomposition of that
 * raster is non-overlapping by construction. That is the whole reason the no-library vector route
 * goes through the mask rather than through the track points.
 *
 * **It is a decomposition, not a contour trace, and that is deliberate.** Marching squares would
 * give fewer and prettier rings, at the cost of getting nesting, winding and ring orientation right
 * in a place where being wrong means revealing ground. Rectangles cannot nest and cannot wind the
 * wrong way. What they cost is ring COUNT, and that count is the measurement this arm exists to
 * produce: section 15p predicts the mask-traced boundary keeps today's staircase and sharpens it,
 * so what is actually in question is whether the geometry is affordable at all.
 *
 * [step] samples the mask every `step` pixels. At 1 the staircase is exactly the mask's own
 * granularity, which is what 15p describes; above 1 the boundary is coarser AND the region grows
 * by up to one step, because a sampled cell counts as revealed only when its sample is revealed -
 * see [REVEALED_ALPHA_CEILING]. Coarser is a legitimate trade for a lower ring count; growing is
 * not, so the sample is the cell's most-fogged corner rather than its centre.
 */
object FogMaskContours {

    /**
     * A pixel counts as revealed when its fog is this faint or fainter.
     *
     * Zero would be the strictest reading, but the renderer feathers a reveal's edge, so a strict
     * test would trace the inside of the feather and leave a fogged fringe the raster does not
     * have. This is the same "mostly revealed" threshold the boundary probes use.
     */
    const val REVEALED_ALPHA_CEILING = 8

    /**
     * The ring budget for one mask.
     *
     * Not a performance guess: a Google `Polygon` carries every hole in one object, and a viewport
     * whose reveals decompose into thousands of rings is the answer "this arm does not fit" rather
     * than a reason to draw a wrong one. Truncation is REPORTED, never silent - a partially
     * decomposed mask under-reveals, which is fail-closed and safe, but it is not the mask and a
     * measurement must not read it as one.
     */
    const val MAX_RECTS = 4_096

    /**
     * Decomposes [mask]'s revealed pixels into rectangles, sampling every [step] pixels.
     *
     * Runs of revealed cells on one row become one rectangle each, and a run is extended downwards
     * while the row below has an identical run. A diagonal corridor therefore merges very little
     * and a broad clearing merges almost completely, which is why the returned count is worth
     * reporting rather than assuming.
     */
    fun decompose(
        mask: FogPixelMask,
        step: Int = 1,
        maxRects: Int = MAX_RECTS,
    ): FogMaskContourResult {
        require(step >= 1) { "step must be at least 1" }
        require(maxRects >= 1) { "maxRects must be at least 1" }

        val cols = (mask.width + step - 1) / step
        val rows = (mask.height + step - 1) / step
        if (cols == 0 || rows == 0) return FogMaskContourResult(emptyList(), truncated = false)

        // One row of open rectangles, keyed by the run that opened them, so a run that continues on
        // the next row extends its rectangle instead of emitting a second one.
        var open: MutableList<Pair<IntRange, Int>> = mutableListOf()
        val out = ArrayList<FogMaskRect>()
        var truncated = false

        fun emit(run: IntRange, top: Int, bottom: Int) {
            if (out.size >= maxRects) {
                truncated = true
                return
            }
            out.add(
                FogMaskRect(
                    left = run.first * step,
                    top = top * step,
                    right = minOf((run.last + 1) * step, mask.width),
                    bottom = minOf(bottom * step, mask.height),
                ),
            )
        }

        for (row in 0 until rows) {
            val runs = revealedRuns(mask, row, cols, step)
            val next = mutableListOf<Pair<IntRange, Int>>()
            for (run in runs) {
                val carried = open.firstOrNull { it.first == run }
                next.add(run to (carried?.second ?: row))
            }
            // Anything open that did not continue is finished at this row.
            for ((run, top) in open) {
                if (runs.none { it == run }) emit(run, top, row)
            }
            open = next
            if (truncated) break
        }
        if (!truncated) {
            for ((run, top) in open) emit(run, top, rows)
        }
        return FogMaskContourResult(out, truncated)
    }

    /**
     * The revealed runs on one sampled row.
     *
     * A sampled cell is revealed only when EVERY mask pixel it stands for is revealed. Sampling one
     * pixel per cell would grow the revealed region by up to a step at every boundary, which on
     * this arm means showing ground the raster fogs - the one error this whole surface must not
     * make. Taking the cell's maximum alpha instead shrinks it, which is fail-closed.
     */
    private fun revealedRuns(
        mask: FogPixelMask,
        row: Int,
        cols: Int,
        step: Int,
    ): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (col in 0 until cols) {
            val revealed = cellIsRevealed(mask, col, row, step)
            if (revealed && start < 0) {
                start = col
            } else if (!revealed && start >= 0) {
                runs.add(start..(col - 1))
                start = -1
            }
        }
        if (start >= 0) runs.add(start..(cols - 1))
        return runs
    }

    private fun cellIsRevealed(mask: FogPixelMask, col: Int, row: Int, step: Int): Boolean {
        val x0 = col * step
        val y0 = row * step
        val x1 = minOf(x0 + step, mask.width)
        val y1 = minOf(y0 + step, mask.height)
        if (x0 >= x1 || y0 >= y1) return false
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                if (mask.alphaAt(x, y) > REVEALED_ALPHA_CEILING) return false
            }
        }
        return true
    }
}
