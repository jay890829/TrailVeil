package app.trailveil.benchmark

import android.os.ParcelFileDescriptor
import android.util.SparseIntArray
import androidx.core.util.size
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * Presentation intervals for a `SurfaceView` layer, read from SurfaceFlinger's TimeStats.
 *
 * ## Why this exists
 *
 * `FrameMetricsAggregator` observes the frames of an ACTIVITY WINDOW. The MapLibre entry map is not
 * in that window: `TrailVeilMapSurface` builds it with `.textureMode(rendersIntoTheWindow)` and the
 * entry map takes the default `false`, so it is a `SurfaceView` composited by SurfaceFlinger on its
 * own layer. Measuring the window therefore measures Compose chrome redraws and never the map.
 *
 * That is not a new discovery in this project - `P1-002` diagnosed it and said so in as many words:
 * *"Activity `gfxinfo` is not used for the map result because MapLibre renders in a native
 * `SurfaceView`; SurfaceFlinger presentation timestamps cover that surface."* It then measured the
 * map correctly with `dumpsys SurfaceFlinger --latency`. `P4-003` later switched the benchmark to
 * the window-level aggregator, and every MapLibre frame figure recorded since has been chrome: 120
 * and 60 in `P4-003`, 40 in `P4-006`, 60 in `P4-008`, and 2 on the designated device on 2026-09-04
 * beside 1096 from the TextureView-backed Google twin in the same session.
 *
 * ## Why not just re-run P1-002's command
 *
 * `dumpsys SurfaceFlinger --latency <layer>` **is dead on Android 16**. Measured on the API 36 image:
 * it returns the refresh period and nothing else, for the BLAST layer, the bare layer name and the
 * window layer alike, before and after real presentation. The legacy per-layer latency history it
 * read is not maintained on the BLAST buffer path.
 *
 * `dumpsys SurfaceFlinger --timestats` is the live equivalent and does track the map's own layer.
 * Its `present2present` histogram is millisecond buckets with counts - the same shape as the
 * `SparseIntArray` the aggregator produces - so the benchmark's existing `summarize()` consumes it
 * unchanged, and p95 and the frozen ratio keep their exact definitions.
 *
 * ## The one thing that must be got right
 *
 * An interval is only a jank measurement while the map is *obliged to draw*. MapLibre renders
 * `WHEN_DIRTY`, so a still map emits nothing, and a quiet stretch between two presentations is
 * recorded as one enormous interval that lands in the frozen buckets - which would make the frozen
 * ratio a measure of quiescence rather than of jank. So this is armed and read around each camera
 * animation individually, and the histograms are summed. The fog-generation waits between
 * animations are outside every measured window by construction, which is the same restriction
 * `P1-002` achieved by gesturing continuously.
 */
internal object SurfaceFlingerPresentIntervals {

    /**
     * Turns TimeStats on and starts a clean window.
     *
     * TimeStats is off by default and is global to SurfaceFlinger, so [disable] must be called when
     * the measurement is finished - leaving it enabled would change how the device behaves for
     * everything measured after this test.
     */
    fun arm() {
        shell("dumpsys SurfaceFlinger --timestats -enable")
        clear()
    }

    /** Starts a fresh window without disturbing the enabled state. */
    fun clear() {
        shell("dumpsys SurfaceFlinger --timestats -clear")
    }

    fun disable() {
        shell("dumpsys SurfaceFlinger --timestats -disable")
    }

    /**
     * The `present2present` histogram for the first layer whose name contains [layerNameFragment],
     * or `null` when TimeStats recorded no such layer.
     *
     * `null` is not an error to be smoothed over: it means the surface presented nothing in the
     * window just measured, which is exactly the condition the caller must fail on rather than
     * quietly report a p95 for.
     */
    fun presentIntervals(layerNameFragment: String): SparseIntArray? {
        val dump = shell("dumpsys SurfaceFlinger --timestats -dump")
        val lines = dump.lineSequence().toList()
        val layerIndex = lines.indexOfFirst { line ->
            line.startsWith(LAYER_NAME_PREFIX) && line.contains(layerNameFragment)
        }
        if (layerIndex < 0) return null

        // Read forward to this layer's histogram, stopping at the next layer so a later layer's
        // numbers can never be attributed to this one.
        for (index in layerIndex + 1 until lines.size) {
            val line = lines[index]
            if (line.startsWith(LAYER_NAME_PREFIX)) return null
            if (line.startsWith(PRESENT_HISTOGRAM_HEADER)) {
                return parseHistogram(lines.getOrNull(index + 1).orEmpty())
            }
        }
        return null
    }

    /**
     * `0ms=0 1ms=0 ... 16ms=101 17ms=26 ...` into the bucket/count shape `summarize` expects.
     *
     * Zero-count buckets are dropped rather than stored: the histogram lists every bucket it knows
     * about, and carrying the empty ones would make `SparseIntArray.size` meaningless as a count
     * of distinct observed durations.
     */
    fun parseHistogram(line: String): SparseIntArray {
        val histogram = SparseIntArray()
        for (token in line.trim().split(' ')) {
            val bucket = token.substringBefore("ms=", missingDelimiterValue = "")
            val count = token.substringAfter("ms=", missingDelimiterValue = "")
            if (bucket.isEmpty() || count.isEmpty()) continue
            val millis = bucket.toIntOrNull() ?: continue
            val frames = count.toIntOrNull() ?: continue
            if (frames > 0) histogram.put(millis, histogram.get(millis) + frames)
        }
        return histogram
    }

    /** Adds [addition]'s counts into [target], so per-animation windows can be summed. */
    fun merge(target: SparseIntArray, addition: SparseIntArray) {
        for (index in 0 until addition.size) {
            val bucket = addition.keyAt(index)
            target.put(bucket, target.get(bucket) + addition.valueAt(index))
        }
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).use { stream ->
            String(stream.readBytes())
        }.also { descriptor.close() }
    }

    private const val LAYER_NAME_PREFIX = "layerName = "
    private const val PRESENT_HISTOGRAM_HEADER = "present2present histogram is as below:"
}
