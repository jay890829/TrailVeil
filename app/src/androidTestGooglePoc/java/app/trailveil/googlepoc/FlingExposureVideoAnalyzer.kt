package app.trailveil.googlepoc

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.core.graphics.get
import kotlin.math.abs

/**
 * `V02-005` stage 3, SP5: on-device screenrecord analyzer (no host ffmpeg). screenrecord
 * captures SurfaceFlinger-composited frames — every frame actually presented — so this is not
 * sampling: the bound's resolution equals the measured inter-frame PTS gap, reported per trial.
 *
 * Per frame, inside the map rect minus exclusion rects: FOG-FAMILY covers the full palette
 * signature space plus H.264 tolerance; MARKER is the magenta clapper window; everything else is
 * EXPOSED. Exposure requires an 8-connected cluster >= [CLUSTER_MINIMUM_PX] and a per-frame
 * exposed area above the measured noise floor.
 */
object FlingExposureVideoAnalyzer {

    data class FrameStat(
        val ptsMillis: Long,
        val exposedPx: Int,
        val largestClusterPx: Int,
        val markerPx: Int,
    )

    data class Analysis(
        val frames: List<FrameStat>,
        val clapperPulses: Int,
        val motionStartMillis: Long,
        val motionEndMillis: Long,
        val frameGapMsMaxMotion: Long,
        val ptsMonotonic: Boolean,
        val videoWidth: Int,
        val videoHeight: Int,
    )

    const val CLUSTER_MINIMUM_PX = 64
    private const val VIDEO_TOLERANCE = 6
    private const val SAMPLE_STRIDE = 3

    fun analyze(
        path: String,
        mapRect: Rect,
        exclusions: List<Rect>,
        markerRect: Rect,
    ): Analysis {
        val ptsMillis = extractPresentationTimes(path)
        val ptsMonotonic = ptsMillis.zipWithNext().all { (a, b) -> b > a }
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<FrameStat>()
        var width = 0
        var height = 0
        try {
            retriever.setDataSource(path)
            val frameCount = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,
            )?.toIntOrNull() ?: ptsMillis.size
            for (index in 0 until minOf(frameCount, ptsMillis.size)) {
                val bitmap = try {
                    retriever.getFrameAtIndex(index)
                } catch (_: Exception) {
                    null
                } ?: continue
                width = bitmap.width
                height = bitmap.height
                frames += statFor(bitmap, ptsMillis[index], mapRect, exclusions, markerRect)
                bitmap.recycle()
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Release failure does not invalidate collected stats.
            }
        }

        // Clapper pulses: rising edges of markerPx above half the marker area.
        val markerArea = markerRect.width() * markerRect.height() / (SAMPLE_STRIDE * SAMPLE_STRIDE)
        val markerOn = frames.map { it.markerPx > markerArea / 2 }
        var pulses = 0
        var motionStart = -1L
        var motionEnd = Long.MAX_VALUE
        markerOn.forEachIndexed { index, on ->
            if (on && (index == 0 || !markerOn[index - 1])) {
                pulses += 1
                when (pulses) {
                    1 -> motionStart = frames[index].ptsMillis
                    2 -> motionEnd = frames[index].ptsMillis
                }
            }
        }
        val motionFrames = frames.filter { it.ptsMillis in motionStart..motionEnd }
        val maxGap = motionFrames.map { it.ptsMillis }.zipWithNext()
            .maxOfOrNull { (a, b) -> b - a } ?: -1L
        return Analysis(
            frames = frames,
            clapperPulses = pulses,
            motionStartMillis = motionStart,
            motionEndMillis = if (motionEnd == Long.MAX_VALUE) -1L else motionEnd,
            frameGapMsMaxMotion = maxGap,
            ptsMonotonic = ptsMonotonic,
            videoWidth = width,
            videoHeight = height,
        )
    }

    private fun extractPresentationTimes(path: String): List<Long> {
        val extractor = MediaExtractor()
        val times = mutableListOf<Long>()
        try {
            extractor.setDataSource(path)
            var track = -1
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    track = index
                    break
                }
            }
            if (track < 0) return emptyList()
            extractor.selectTrack(track)
            while (true) {
                val time = extractor.sampleTime
                if (time < 0) break
                times += time / 1_000L
                if (!extractor.advance()) break
            }
        } catch (_: Exception) {
            return times
        } finally {
            extractor.release()
        }
        return times
    }

    private fun statFor(
        bitmap: Bitmap,
        ptsMillis: Long,
        mapRect: Rect,
        exclusions: List<Rect>,
        markerRect: Rect,
    ): FrameStat {
        var exposed = 0
        var markerCount = 0
        // Exposure map on the strided grid for 8-connected clustering.
        val columns = (mapRect.width() + SAMPLE_STRIDE - 1) / SAMPLE_STRIDE
        val rows = (mapRect.height() + SAMPLE_STRIDE - 1) / SAMPLE_STRIDE
        val exposedGrid = BooleanArray(columns * rows)
        var y = mapRect.top
        var gridY = 0
        while (y < mapRect.bottom && y < bitmap.height) {
            var x = mapRect.left
            var gridX = 0
            while (x < mapRect.right && x < bitmap.width) {
                val pixel = bitmap[x, y]
                when {
                    markerRect.contains(x, y) -> {
                        if (isMarker(pixel)) markerCount += 1
                    }
                    exclusions.any { it.contains(x, y) } -> Unit
                    isFogFamily(pixel) -> Unit
                    isMarker(pixel) -> Unit
                    else -> {
                        exposed += 1
                        exposedGrid[gridY * columns + gridX] = true
                    }
                }
                x += SAMPLE_STRIDE
                gridX += 1
            }
            y += SAMPLE_STRIDE
            gridY += 1
        }
        val largestCluster = largestCluster(exposedGrid, columns, rows) *
            SAMPLE_STRIDE * SAMPLE_STRIDE
        return FrameStat(
            ptsMillis = ptsMillis,
            exposedPx = exposed * SAMPLE_STRIDE * SAMPLE_STRIDE,
            largestClusterPx = largestCluster,
            markerPx = markerCount,
        )
    }

    private fun isFogFamily(pixel: Int): Boolean {
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return red in (31 - VIDEO_TOLERANCE)..(31 + 12 + VIDEO_TOLERANCE) &&
            green in (38 - VIDEO_TOLERANCE)..(38 + 12 + VIDEO_TOLERANCE) &&
            blue in (43 - VIDEO_TOLERANCE)..(43 + 12 + VIDEO_TOLERANCE)
    }

    private fun isMarker(pixel: Int): Boolean {
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return red > 200 && green < 90 && blue > 200
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

    /** Marker-agnostic delta check used by the calibration/falsify paths. */
    fun exposureLooksLikeBasemap(stat: FrameStat, mapArea: Int, thresholdPct: Double): Boolean =
        stat.exposedPx * 100.0 / mapArea >= thresholdPct

    @Suppress("unused")
    private fun channelDelta(a: Int, b: Int): Int = abs(a - b)
}
