package app.trailveil.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One row per occupied coarse cell — the materialised summary the world-zoom read uses instead of
 * visiting every point.
 *
 * `P4-037`. Maintained beside each accepted point rather than computed on demand, because the
 * measurement showed computing it on demand saves almost nothing: the cost of a world settle is
 * visiting rows, and an aggregate visits every one. See [TrackPointCells].
 *
 * **This table is derived state, and it is derivable from `track_points` alone.** That is what makes
 * it safe to rebuild: the migration backfills it by scanning the points once, and any future repair
 * can do the same. It holds no information of its own, so losing it costs a rebuild and never a
 * track.
 *
 * The primary key is the cell itself, which is what makes the write path an `INSERT OR IGNORE` — the
 * second point in a cell is a no-op rather than a read followed by a decision.
 */
@Entity(
    tableName = "track_point_cells",
    primaryKeys = ["lat_cell", "lon_cell"],
)
data class TrackPointCellEntity(
    @ColumnInfo(name = "lat_cell")
    val latitudeCell: Int,
    @ColumnInfo(name = "lon_cell")
    val longitudeCell: Int,
)
