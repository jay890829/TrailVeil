package app.trailveil.map.fog

import java.util.IdentityHashMap

/**
 * Experimental, synchronous scope for a factory-owned immutable viewport read. No scene escapes
 * selection/painting; a coroutine must not suspend inside block. Custom coordinators default off.
 */
internal object FogRasterProjectionScope {
    private val active = ThreadLocal<Frame?>()
    private const val MAX_ACCOUNTED_BYTES = 8L * 1024 * 1024

    fun current(zoom: Int, style: FogRenderStyle): Frame? = active.get()?.takeIf {
        it.zoom == zoom && it.style == style
    }

    fun <T> withFrame(enabled: Boolean, zoom: Int, style: FogRenderStyle, segments: List<TrackSegment>,
        checkActive: () -> Unit, block: () -> T): T {
        val previous = active.get()
        val frame = if (enabled && segments.isNotEmpty()) create(zoom, style, segments, checkActive) else null
        active.set(frame)
        try { return block() } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    private fun create(zoom: Int, style: FogRenderStyle, segments: List<TrackSegment>, checkActive: () -> Unit): Frame? {
        val frame = Frame(zoom, style, checkActive)
        var points = 0L
        segments.forEachIndexed { index, segment ->
            if (index % 256 == 0) checkActive()
            if (!frame.slots.containsKey(segment)) {
                val count = segment.points.size
                val next = points + count
                // Four primitive doubles per point plus a conservative metadata accounting unit.
                if (next * 32 + (frame.slots.size + 1L) * 96 > MAX_ACCOUNTED_BYTES) return null
                frame.slots[segment] = Slot(segment.points, points.toInt() * 4, count, frame)
                points = next
            }
        }
        frame.values = DoubleArray(points.toInt() * 4)
        return frame
    }

    class Frame internal constructor(val zoom: Int, val style: FogRenderStyle, val checkActive: () -> Unit) {
        internal val slots = IdentityHashMap<TrackSegment, Slot>()
        internal var values = DoubleArray(0)
        val worldSize = style.tileSize.toDouble() * (1 shl zoom)
        fun segment(segment: TrackSegment): Slot? = slots[segment]
    }

    class Slot internal constructor(private val points: List<GeoPoint>, private val offset: Int,
        private val count: Int, private val frame: Frame) {
        private var computed = 0
        val values: DoubleArray get() = frame.values

        /** Ascending prefixes preserve the original first-point and unwrapping dependency. */
        fun ensure(index: Int): Int {
            check(index in 0 until count) { "projection input length changed inside viewport" }
            val data = frame.values
            while (computed <= index) {
                if (computed % 256 == 0) frame.checkActive()
                val point = points[computed]
                val base = offset + computed * 4
                val wrappedX = WebMercator.normalizedX(point.longitude) * frame.worldSize
                val y = WebMercator.normalizedY(point.latitude) * frame.worldSize
                val x = if (computed == 0) wrappedX else WebMercator.unwrapWorldX(data[base - 4], wrappedX, frame.worldSize)
                val radius = frame.style.revealRadiusMeters /
                    WebMercator.metersPerPixel(point.latitude, frame.zoom, frame.style.tileSize)
                data[base] = x; data[base + 1] = y; data[base + 2] = radius; data[base + 3] = wrappedX
                computed++
            }
            return offset + index * 4
        }
    }
}
