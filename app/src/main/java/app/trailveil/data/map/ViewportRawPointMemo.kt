package app.trailveil.data.map

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Optional raw rows retained only between raster reads and one native preparation. The owner must
 * seal capture before native reads and close in finally. Ordinary readers carry no such context.
 * Replacement validity is separate from MAX(point id), which detects committed append changes.
 * This relies on immutable existing point fields and AUTOINCREMENT append. Every other canonical
 * mutation must change the owner's replacement epoch; MAX(id) alone cannot detect delete/update.
 * Estimated bytes are accounting units, not a measurement or promise of process/RSS usage.
 */
internal class ViewportRawPointMemo(
    private val isCurrent: () -> Boolean,
    private val maxRows: Int = 32_768,
    private val maxEntries: Int = 2,
    private val maxAccountedBytes: Long = 4L * 1024 * 1024,
    private val requiredCaptureBounds: ViewportBounds? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ViewportRawPointMemo>
    private enum class Phase { CAPTURE, REUSE, CLOSED }
    private data class Entry(
        val owner: Any, val south: Double, val north: Double, val interval: LongitudeInterval,
        val pointId: Long, val rows: List<ViewportTrackPoint>,
    )
    private val mutex = Mutex()
    private val entries = ArrayList<Entry>()
    private var phase = Phase.CAPTURE
    private var retainedRows = 0
    private var accountedBytes = 0L

    init { require(maxRows >= 0 && maxEntries >= 0 && maxAccountedBytes >= 0L) }

    suspend fun seal() = mutex.withLock { if (phase == Phase.CAPTURE) phase = Phase.REUSE }
    suspend fun close() = mutex.withLock { discard(); phase = Phase.CLOSED }

    suspend fun read(
        owner: Any,
        south: Double,
        north: Double,
        interval: LongitudeInterval,
        latestPointId: suspend () -> Long,
        query: suspend () -> List<ViewportTrackPoint>,
    ): List<ViewportTrackPoint> = mutex.withLock {
        val context = currentCoroutineContext()
        context.ensureActive()
        suspend fun load(): List<ViewportTrackPoint> {
            context.ensureActive()
            return query().also { context.ensureActive() }
        }
        if (phase == Phase.CLOSED || !isCurrent()) {
            discard()
            return@withLock load()
        }
        if (phase == Phase.CAPTURE) {
            requiredCaptureBounds?.let { required ->
                if (required.west > required.east || south > required.south || north < required.north ||
                    interval.west > required.west || interval.east < required.east) return@withLock load()
            }
            if (entries.size >= maxEntries || retainedRows >= maxRows || accountedBytes >= maxAccountedBytes) return@withLock load()
            val before = latestPointId()
            val rows = load()
            context.ensureActive()
            val cost = rows.size.toLong() * 96L + 64L
            if (rows.size <= maxRows - retainedRows && cost <= maxAccountedBytes - accountedBytes &&
                isCurrent() && before == latestPointId() && isCurrent()) {
                context.ensureActive()
                entries += Entry(owner, south, north, interval, before, rows.toList())
                retainedRows += rows.size
                accountedBytes += cost
            }
            context.ensureActive()
            return@withLock rows
        }
        val entry = entries.firstOrNull { candidate ->
            candidate.owner === owner && candidate.south <= south && candidate.north >= north &&
                candidate.interval.west <= interval.west && candidate.interval.east >= interval.east
        } ?: return@withLock load()
        if (entry.pointId != latestPointId() || !isCurrent()) {
            discard()
            return@withLock load()
        }
        val selected = ArrayList<ViewportTrackPoint>()
        entry.rows.forEachIndexed { index, row ->
            if (index % 256 == 0) context.ensureActive()
            if (row.latitude >= south && row.latitude <= north &&
                row.longitude >= interval.west && row.longitude <= interval.east) selected += row
        }
        context.ensureActive()
        if (entry.pointId != latestPointId() || !isCurrent()) {
            discard()
            return@withLock load()
        }
        context.ensureActive()
        // The data source still owns deduplication, ordering and segment-gap reconstruction.
        selected
    }

    private fun discard() { entries.clear(); retainedRows = 0; accountedBytes = 0L }
}
