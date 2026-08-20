package app.trailveil.data.db

/**
 * Reads the fog viewport the way production reads it, from inside `data.db`.
 *
 * `RoomViewportTrackPointReader` lives in `data.map` and takes the same DAO; this exists only so a
 * `data.db` test can ask the production query "can you see this point" without restating the SQL,
 * which is how two earlier measurement attempts in this area ended up measuring their own
 * construction instead of the app's.
 */
internal object RoomViewportTrackPointReaderProbe {
    private const val HALF_BOX_DEGREES = 0.001

    suspend fun rowsAround(dao: RecordingDao, latitude: Double, longitude: Double): Int {
        val south = latitude - HALF_BOX_DEGREES
        val north = latitude + HALF_BOX_DEGREES
        val buckets = requireNotNull(LatitudeBuckets.covering(south, north)) {
            "a box this small must be expressible as equalities"
        }
        return dao.fogPointsInBucketedBox(
            latitudeBuckets = buckets,
            south = south,
            west = longitude - HALF_BOX_DEGREES,
            north = north,
            east = longitude + HALF_BOX_DEGREES,
        ).size
    }
}
