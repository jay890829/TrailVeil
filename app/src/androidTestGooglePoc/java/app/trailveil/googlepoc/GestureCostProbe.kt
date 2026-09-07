package app.trailveil.googlepoc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.util.SparseIntArray
import android.view.View
import androidx.core.app.FrameMetricsAggregator
import androidx.core.util.isEmpty
import androidx.core.util.size

/**
 * The two numbers `V03-011` needs that the pixel audit does not measure: frame cost and memory.
 *
 * The audit samples the surface at roughly 8 Hz to answer "what did the user see"; it cannot answer
 * "what did this cost", and its `maxFrameGapMillis` is the sampler's own inter-sample gap - a
 * diagnosis of a starved looper, not a render time. The spike has to report a hitch and a memory
 * figure per arm, so those come from here.
 *
 * Frame cost comes from [FrameMetricsAggregator], the instrument the fog scale benchmark next door
 * already uses, armed around the gesture window alone. **The number that matters for this spike is
 * [GestureCost.worstFrameMillis]**, not the p95: the question is whether swapping a mosaic or
 * updating a geometry drops ONE frame badly, and a percentile is designed to hide exactly that. The
 * p95 and the frozen count come along because a single worst frame is easy to over-read; the p95 is
 * computed the way the two scale benchmarks compute theirs, so the numbers can be compared.
 *
 * Memory is two snapshots, before and after, not a peak. The neighbouring benchmark gets its peak
 * from a `Debug.getPss()` sampler on a worker thread, which is right for a five-minute scale run and
 * wrong here: `getPss` walks `smaps` and costs milliseconds, so a sampler would perturb the frame
 * numbers this class exists to measure. Both readings are therefore taken OUTSIDE the armed window -
 * before the aggregator is added and after it is removed - and the arm comparison reads the delta.
 * That is a weaker claim than a peak and is named as one: a transient spike inside the gesture is
 * invisible to it. It answers the question actually being asked, which is whether one large texture
 * costs more resident memory than many tiles. `summary.graphics` is the line that moves for a fog
 * design and is reported apart from the Java heap for that reason.
 */
internal class GestureCostProbe private constructor(private val activity: Activity) {
    private val aggregator = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
    private var armed = false
    private var before: GestureMemory = GestureMemory.NOT_MEASURED

    fun start() {
        if (armed) return
        // Read memory first: this call walks the process maps and takes milliseconds, so it must
        // not land inside the window whose frame durations are the point of the measurement.
        before = readMemory()
        aggregator.add(activity)
        armed = true
    }

    /** Safe to call twice; the second call reports nothing rather than throwing. */
    fun stop(): GestureCost {
        if (!armed) return GestureCost.NOT_MEASURED
        armed = false
        val histogram = try {
            aggregator.remove(activity)?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
        } catch (_: RuntimeException) {
            // The aggregator throws if the window went away under it. A terminal surface swap does
            // exactly that, and reporting it is the audit's job, not this class's.
            null
        }
        aggregator.stop()
        return summarize(histogram).copy(before = before, after = readMemory())
    }

    private fun summarize(histogram: SparseIntArray?): GestureCost {
        if (histogram == null || histogram.isEmpty()) return GestureCost.NOT_MEASURED
        var total = 0
        var frozen = 0
        var worstMillis = 0
        repeat(histogram.size) { index ->
            val durationMillis = histogram.keyAt(index)
            val count = histogram.valueAt(index)
            total += count
            if (durationMillis >= FROZEN_FRAME_MILLIS) frozen += count
            if (count > 0 && durationMillis > worstMillis) worstMillis = durationMillis
        }
        if (total == 0) return GestureCost.NOT_MEASURED

        val percentileRank = (total * 95 + 99) / 100
        var cumulative = 0
        var p95Millis = 0
        repeat(histogram.size) { index ->
            if (cumulative < percentileRank) {
                cumulative += histogram.valueAt(index)
                p95Millis = histogram.keyAt(index)
            }
        }
        return GestureCost(
            frames = total,
            worstFrameMillis = worstMillis,
            p95FrameMillis = p95Millis,
            frozenFrames = frozen,
            before = GestureMemory.NOT_MEASURED,
            after = GestureMemory.NOT_MEASURED,
        )
    }

    private fun readMemory(): GestureMemory {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        fun stat(name: String): Int = info.getMemoryStat(name)?.toIntOrNull() ?: -1
        return GestureMemory(
            totalPssKb = info.totalPss,
            javaHeapKb = stat("summary.java-heap"),
            nativeHeapKb = stat("summary.native-heap"),
            graphicsKb = stat("summary.graphics"),
        )
    }

    companion object {
        /** The two scale benchmarks' definition, so the frozen counts are comparable. */
        const val FROZEN_FRAME_MILLIS = 700

        /**
         * Null when the view is not hosted by an Activity, which is a reason to report "not
         * measured" rather than to fail: this is an extra number on an existing audit, and it must
         * never be the thing that turns a fog trial red.
         */
        fun forView(view: View): GestureCostProbe? =
            activityOf(view.context)?.let(::GestureCostProbe)

        private fun activityOf(context: Context?): Activity? {
            var current = context
            while (current is ContextWrapper) {
                if (current is Activity) return current
                current = current.baseContext
            }
            return null
        }
    }
}

/** One reading, not a peak. See [GestureCostProbe]. */
internal data class GestureMemory(
    val totalPssKb: Int,
    val javaHeapKb: Int,
    val nativeHeapKb: Int,
    val graphicsKb: Int,
) {
    val measured: Boolean get() = totalPssKb > 0

    fun describe(prefix: String): String =
        "${prefix}PssKb=$totalPssKb ${prefix}JavaKb=$javaHeapKb " +
            "${prefix}NativeKb=$nativeHeapKb ${prefix}GfxKb=$graphicsKb"

    companion object {
        val NOT_MEASURED = GestureMemory(-1, -1, -1, -1)
    }
}

internal data class GestureCost(
    val frames: Int,
    val worstFrameMillis: Int,
    val p95FrameMillis: Int,
    val frozenFrames: Int,
    val before: GestureMemory,
    val after: GestureMemory,
) {
    /**
     * A floor on the frame count, not a presence check: a window that reports a handful of frames
     * has not measured a smooth gesture, it has failed to see the map, and its worst frame must not
     * then be quoted as a hitch.
     */
    val measured: Boolean get() = frames >= MINIMUM_FRAMES

    /** The arm comparison's memory number. `-1` when either reading is missing. */
    val graphicsDeltaKb: Int
        get() = if (before.measured && after.measured) after.graphicsKb - before.graphicsKb else -1

    val totalPssDeltaKb: Int
        get() = if (before.measured && after.measured) after.totalPssKb - before.totalPssKb else -1

    fun describe(): String =
        "costFrames=$frames costMeasured=$measured " +
            "worstFrameMs=$worstFrameMillis p95FrameMs=$p95FrameMillis " +
            "frozenFrames=$frozenFrames " +
            "${before.describe("before")} ${after.describe("after")} " +
            "deltaPssKb=$totalPssDeltaKb deltaGfxKb=$graphicsDeltaKb"

    companion object {
        /** Below this the window did not see the map; the numbers are not measurements. */
        const val MINIMUM_FRAMES = 10

        val NOT_MEASURED = GestureCost(
            frames = 0,
            worstFrameMillis = -1,
            p95FrameMillis = -1,
            frozenFrames = 0,
            before = GestureMemory.NOT_MEASURED,
            after = GestureMemory.NOT_MEASURED,
        )
    }
}
