package app.trailveil.googlepoc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import app.trailveil.map.fog.FogTileColor
import app.trailveil.map.fog.FogTilePngCodec

internal object GestureExposurePixelsB45Reference {
    /** Every 4th pixel in both axes: ~34k samples of a 1080x2000 map, cheap enough per frame. */
    const val SAMPLE_STRIDE_PX = 4

    /** Compositor edge artefacts on a scaled readback are not a fog verdict. */
    const val EDGE_INSET_PX = 6

    /**
     * The band the entry screen's own controls cannot reach: the menu button and notice column
     * grow down from the top, the recentre button sits at the bottom end, and the SDK watermark
     * and compass are in the bottom-left and top-right corners. Only ever applied to a composited
     * capture; surface readbacks contain no app chrome at all and are judged whole.
     */
    const val BAND_TOP_FRACTION = 0.34
    const val BAND_BOTTOM_FRACTION = 0.70

    /** The channel name a whole-screen crop reports. */
    const val WHOLE_SCREEN_CHANNEL = "UI_AUTOMATION"

    /** Surface readbacks: the SDK renderer's own output, with no app chrome and no view overlay. */
    const val PIXEL_COPY_CHANNEL = "PIXEL_COPY_SURFACE"
    const val TEXTURE_VIEW_CHANNEL = "TEXTURE_VIEW"

    /** A sample whose capture produced nothing. Never silently dropped; counted and asserted. */
    const val UNJUDGED_CHANNEL = "none"

    val SURFACE_CHANNELS = setOf(PIXEL_COPY_CHANNEL, TEXTURE_VIEW_CHANNEL)

    /** Classification tolerance for a scaled/compressed readback. */
    const val COVER_TOLERANCE = 6

    /**
     * Proof tolerance, matching `GoogleProductionLauncherMapHostTest.assertFogCoverPixels`'s window
     * exactly. That helper is private to its own class, so the window is reproduced rather than
     * called; the numbers are the same +-2 per channel plus a hard `alpha == 255`.
     */
    const val COVER_PROOF_TOLERANCE = 2

    data class Tally(
        val analyzedPx: Int,
        val excludedPx: Int,
        val exposedPx: Int,
        val largestClusterPx: Int,
    ) {
        val exposedPct: Double
            get() = if (analyzedPx <= 0) 0.0 else exposedPx * 100.0 / analyzedPx

        val largestClusterPct: Double
            get() = if (analyzedPx <= 0) 0.0 else largestClusterPx * 100.0 / analyzedPx

        val excludedPct: Double
            get() = excludedPx * 100.0 / (analyzedPx + excludedPx).coerceAtLeast(1)

        fun describe(): String =
            "analyzed=$analyzedPx excluded=$excludedPx exposed=$exposedPx " +
                "cluster=$largestClusterPx exposedPct=${"%.3f".format(exposedPct)} " +
                "clusterPct=${"%.3f".format(largestClusterPct)}"
    }

    /**
     * True for the safety cover. `V02-012` design 2: `GoogleFogSafetyOverlay` paints the default
     * fog colour at the shared fog opacity, so on screen it is fog over basemap - the codec's
     * revealed-fog window - and hides ground exactly as revealed fog does.
     */
    fun isSafetyCover(pixel: Int): Boolean = isCoverPixel(pixel, COVER_TOLERANCE)

    /** The strict proof window: the cover windows with only the +-2 read-back allowance. */
    fun isExactSafetyCover(pixel: Int): Boolean = isCoverPixel(pixel, COVER_PROOF_TOLERANCE)

    /**
     * A covered pixel is anything the fog-coloured cover can produce: over the basemap (every
     * overlay hidden beneath it), over a revealed generation verified beneath it (design 2b), or
     * over a label. The cover's own contribution is the floor, so a bare label reads below it and
     * bare basemap of the assumed lightness above it.
     */
    private fun isCoverPixel(pixel: Int, tolerance: Int): Boolean =
        FogTilePngCodec.matchesBeneathCover(
            FogTileColor(Color.red(pixel), Color.green(pixel), Color.blue(pixel)),
            tolerance,
        )

    fun regionFor(bitmap: Bitmap, channel: String): Rect {
        val inset = Rect(
            EDGE_INSET_PX,
            EDGE_INSET_PX,
            bitmap.width - EDGE_INSET_PX,
            bitmap.height - EDGE_INSET_PX,
        )
        if (channel != WHOLE_SCREEN_CHANNEL) return inset
        return Rect(
            inset.left,
            (bitmap.height * BAND_TOP_FRACTION).toInt(),
            inset.right,
            (bitmap.height * BAND_BOTTOM_FRACTION).toInt(),
        )
    }

    /**
     * Counts pixels that are neither fog-family nor the opaque cover, and the largest 8-connected
     * run of them, on the strided grid. The cluster rule is
     * [FlingExposureVideoAnalyzer.CLUSTER_MINIMUM_PX] so that a leak means the same thing here as
     * it does in SP5's video analyzer.
     */
    fun tally(bitmap: Bitmap, region: Rect, exclusions: List<Rect>): Tally {
        val left = region.left.coerceAtLeast(0)
        val top = region.top.coerceAtLeast(0)
        val right = region.right.coerceAtMost(bitmap.width)
        val bottom = region.bottom.coerceAtMost(bitmap.height)
        val width = right - left
        if (width < SAMPLE_STRIDE_PX || bottom - top < SAMPLE_STRIDE_PX) {
            return Tally(0, 0, 0, 0)
        }
        val columns = (width + SAMPLE_STRIDE_PX - 1) / SAMPLE_STRIDE_PX
        val rows = (bottom - top + SAMPLE_STRIDE_PX - 1) / SAMPLE_STRIDE_PX
        val exposedGrid = BooleanArray(columns * rows)
        val row = IntArray(width)
        var analyzed = 0
        var excluded = 0
        var exposed = 0
        var y = top
        var gridY = 0
        while (y < bottom && gridY < rows) {
            bitmap.getPixels(row, 0, width, left, y, width, 1)
            var x = 0
            var gridX = 0
            while (x < width && gridX < columns) {
                val screenX = left + x
                if (exclusions.none { rect -> rect.contains(screenX, y) }) {
                    analyzed += 1
                    val pixel = row[x]
                    if (!SpikeCaptureSupport.isFogFamily(pixel) && !isSafetyCover(pixel)) {
                        exposed += 1
                        exposedGrid[gridY * columns + gridX] = true
                    }
                } else {
                    excluded += 1
                }
                x += SAMPLE_STRIDE_PX
                gridX += 1
            }
            y += SAMPLE_STRIDE_PX
            gridY += 1
        }
        val scale = SAMPLE_STRIDE_PX * SAMPLE_STRIDE_PX
        return Tally(
            analyzedPx = analyzed * scale,
            excludedPx = excluded * scale,
            exposedPx = exposed * scale,
            largestClusterPx = largestCluster(exposedGrid, columns, rows) * scale,
        )
    }

    private fun largestCluster(grid: BooleanArray, columns: Int, rows: Int): Int {
        val visited = BooleanArray(grid.size)
        var largest = 0
        val stack = ArrayDeque<Int>()
        for (start in grid.indices) {
            if (!grid[start] || visited[start]) continue
            var size = 0
            stack.addLast(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                size += 1
                val cellX = cell % columns
                val cellY = cell / columns
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cellX + dx
                        val ny = cellY + dy
                        if (nx !in 0 until columns || ny !in 0 until rows) continue
                        val neighbour = ny * columns + nx
                        if (grid[neighbour] && !visited[neighbour]) {
                            visited[neighbour] = true
                            stack.addLast(neighbour)
                        }
                    }
                }
            }
            largest = maxOf(largest, size)
        }
        return largest
    }
}

