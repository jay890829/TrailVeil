package app.trailveil.data.map

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.annotation.RequiresApi
import androidx.room.useReaderConnection
import app.trailveil.data.db.FOG_POINTS_IN_LONGITUDE_INTERVAL
import app.trailveil.data.db.TrailVeilDatabase
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** No persistent connection, schema changes, or reads outside a caller's existing transaction. */
@RequiresApi(35)
internal fun RoomViewportTrackPointReader.enableWideRawReads(database: TrailVeilDatabase) {
    wideRawRead = { south, north, interval ->
        database.useReaderConnection { connection ->
            // Reuse Room's context to detect an inherited (including suspending) transaction.
            // A separate read-only connection must not replace that transaction's snapshot.
            if (connection.inTransaction()) null else {
                val path = database.openHelper.readableDatabase.path
                if (path.isNullOrEmpty() || path == ":memory:") null else {
                    val context = currentCoroutineContext()
                    try {
                        readRawWidePoints(path, south, north, interval, context)
                    } catch (_: SQLiteException) {
                        // The optional connection can fail while Room's existing connection is
                        // still usable. Fall back only for SQLite failures, never cancellation
                        // or a programming error in column decoding.
                        context.ensureActive()
                        null
                    }
                }
            }
        }
    }
}

/** Synchronous from open through close: SQLiteRawStatement never crosses a suspension/thread. */
@RequiresApi(35)
private fun readRawWidePoints(path: String, south: Double, north: Double,
    interval: LongitudeInterval, context: CoroutineContext): List<ViewportTrackPoint> {
    context.ensureActive()
    return SQLiteDatabase.openDatabase(path, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
        check(database.isReadOnly)
        database.beginTransactionReadOnly()
        try {
            database.createRawStatement(FOG_POINTS_IN_LONGITUDE_INTERVAL).use statementRow@ { statement ->
                statement.bindDouble(statement.getParameterIndex(":south"), south)
                statement.bindDouble(statement.getParameterIndex(":north"), north)
                statement.bindDouble(statement.getParameterIndex(":west"), interval.west)
                statement.bindDouble(statement.getParameterIndex(":east"), interval.east)
                // Column names describe the current row on API 35. Querying them before
                // step() can enter that platform's fatal JNI error path.
                context.ensureActive()
                if (!statement.step()) {
                    context.ensureActive()
                    return@statementRow emptyList<ViewportTrackPoint>()
                }
                context.ensureActive()
                val columns = (0 until statement.resultColumnCount).associateBy(statement::getColumnName)
                val id = columns.getValue("point_id"); val session = columns.getValue("session_id")
                val segment = columns.getValue("segment_id"); val segmentSequence = columns.getValue("segment_sequence")
                val pointSequence = columns.getValue("point_sequence")
                val latitude = columns.getValue("latitude"); val longitude = columns.getValue("longitude")
                val points = ArrayList<ViewportTrackPoint>()
                do {
                    points += ViewportTrackPoint(statement.getColumnLong(id), statement.getColumnLong(session),
                        statement.getColumnLong(segment), statement.getColumnLong(segmentSequence),
                        statement.getColumnLong(pointSequence), statement.getColumnDouble(latitude), statement.getColumnDouble(longitude))
                    if (points.size % 256 == 0) context.ensureActive()
                } while (statement.step())
                context.ensureActive(); points
            }
        } finally { database.endTransaction() }
    }
}
