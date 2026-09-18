package app.trailveil.data.map

import android.database.Cursor
import app.trailveil.data.db.TrackPointCells
import app.trailveil.data.db.LatitudeBuckets
import app.trailveil.data.db.RecordingDao
import app.trailveil.map.fog.GeoPoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Room-backed viewport projection that exposes no map-provider-specific types. */
internal class RoomViewportTrackPointReader(
    private val dao: RecordingDao,
) : ViewportTrackPointReader {
    /** Harness-only optional decoder; null preserves Room's cursor and constructor ABI. */
    internal var wideRawRead: (suspend (Double, Double, LongitudeInterval) -> List<ViewportTrackPoint>?)? = null
    override suspend fun read(
        south: Double,
        north: Double,
        interval: LongitudeInterval,
    ): List<ViewportTrackPoint> {
        val context = currentCoroutineContext()
        context.ensureActive()
        suspend fun query(): List<ViewportTrackPoint> {
            val raw = wideRawRead
            if (raw != null && LatitudeBuckets.of(maxOf(south, north)) -
                LatitudeBuckets.of(minOf(south, north)) + 1 > LatitudeBuckets.MAX_BUCKETS) {
                raw(south, north, interval)?.let { return it }
            }
            return dao.withFogPointCursor(south, north, interval.west, interval.east) { cursor ->
                readViewportPoints(cursor, context)
            }
        }
        return context[ViewportRawPointMemo]?.read(dao, south, north, interval, dao::latestPersistedPointId, ::query)
            ?: query()
    }

    /**
     * `P4-037`: the occupied cells this box touches, as their centres.
     *
     * Bounds are converted to cell indices here rather than in SQL, by the same functions the write
     * path uses, so the two cannot disagree about where a cell begins.
     *
     * The saving is the table's SIZE, not a narrowed scan — at world zoom the box has no bound to
     * narrow, and the measured plan constrains on `lat_cell` only. `fogCellsInBox` records the plan
     * and why that is the expected answer rather than a shortfall.
     */
    override suspend fun readCoarseCells(
        south: Double,
        north: Double,
        interval: LongitudeInterval,
    ): List<GeoPoint> =
        dao.fogCellsInBox(
            southCell = TrackPointCells.latitudeCellOf(minOf(south, north)),
            northCell = TrackPointCells.latitudeCellOf(maxOf(south, north)),
            westCell = TrackPointCells.longitudeCellOf(minOf(interval.west, interval.east)),
            eastCell = TrackPointCells.longitudeCellOf(maxOf(interval.west, interval.east)),
        ).map { cell ->
            GeoPoint(
                latitude = TrackPointCells.latitudeAtCentreOf(cell.latitudeCell),
                longitude = TrackPointCells.longitudeAtCentreOf(cell.longitudeCell),
            )
        }

}

/** The DAO owns/always closes the cursor; this decoder creates only the final provider-neutral rows. */
internal fun readViewportPoints(cursor: Cursor, context: CoroutineContext): List<ViewportTrackPoint> {
    context.ensureActive()
    val id = cursor.getColumnIndexOrThrow("point_id")
    val session = cursor.getColumnIndexOrThrow("session_id")
    val segment = cursor.getColumnIndexOrThrow("segment_id")
    val segmentSequence = cursor.getColumnIndexOrThrow("segment_sequence")
    val pointSequence = cursor.getColumnIndexOrThrow("point_sequence")
    val latitude = cursor.getColumnIndexOrThrow("latitude")
    val longitude = cursor.getColumnIndexOrThrow("longitude")
    val points = ArrayList<ViewportTrackPoint>()
    while (true) {
        if (points.size % 256 == 0) context.ensureActive()
        if (!cursor.moveToNext()) break
        points += ViewportTrackPoint(cursor.getLong(id), cursor.getLong(session), cursor.getLong(segment),
            cursor.getLong(segmentSequence), cursor.getLong(pointSequence), cursor.getDouble(latitude), cursor.getDouble(longitude))
    }
    context.ensureActive()
    return points
}
