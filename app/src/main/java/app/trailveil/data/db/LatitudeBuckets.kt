package app.trailveil.data.db

import kotlin.math.floor

/**
 * A coarse latitude bucket, so the fog viewport read can bound BOTH dimensions.
 *
 * SQLite stops constraining an index scan at the first RANGE predicate, so a composite
 * `(latitude, longitude)` index narrows by the latitude band alone and the longitude half of the
 * viewport box buys nothing — on the spatially concentrated table a real user produces, that band
 * can hold the entire dataset. An `IN` list is an EQUALITY constraint, so turning the latitude
 * range into a set of equalities lets the NEXT index column stay range-bounded: measured plan,
 * `SEARCH p USING INDEX index_track_points_lat_bucket_longitude (lat_bucket=? AND longitude>? AND
 * longitude<?)`. No space-filling curve is needed, and an equality set on one axis with a range on
 * the other IS the rectangle — a Morton cover approximates it with squares and over-covers.
 *
 * The bucket is coarse on purpose: [BUCKETS_PER_DEGREE] gives about 223 m of latitude per bucket,
 * so a viewport box picks up at most two bucket heights of extra span, and the exact
 * `latitude BETWEEN` predicate still decides membership. The bucket narrows the scan; it never
 * decides the answer.
 */
internal object LatitudeBuckets {
    const val BUCKETS_PER_DEGREE = 500.0

    /**
     * How tall a band the equality list can still express, in degrees.
     *
     * This is the real design input: coarser than render zoom 10 no index helps, because the box
     * already contains everything the table holds. [MAX_BUCKETS] is DERIVED from it rather than
     * chosen alongside it — the two used to be tethered only by this comment, so retuning
     * [BUCKETS_PER_DEGREE] would have silently changed how tall a band still qualifies.
     */
    const val MAX_BAND_DEGREES = 1.28

    /**
     * Fixed arity for the `IN` list, padded with [PADDING_BUCKET].
     *
     * Padding rather than a variable-length list so Room prepares ONE statement instead of one per
     * distinct list size; measured identical plans and byte-identical rows at 6, 63, 128, 512 and
     * 640 slots. At the shipped granularity this is 640, which covers a whole-screen box at render
     * zoom 10 at the equator (583 buckets) with margin, and every finer zoom at every latitude on
     * Earth.
     */
    const val MAX_BUCKETS = (MAX_BAND_DEGREES * BUCKETS_PER_DEGREE).toInt()

    /**
     * Impossible by construction: [TrackPointEntity] requires latitude in [-90, 90], so a real
     * bucket is in [0, 90000]. A negative can never match a row, which is what makes padding free.
     */
    const val PADDING_BUCKET = -1

    fun of(latitude: Double): Int = floor((latitude + 90.0) * BUCKETS_PER_DEGREE).toInt()

    /**
     * Every bucket a latitude band touches, padded to [MAX_BUCKETS] — or null when the band is too
     * tall to express as equalities, which is the caller's signal to fall back to the plain range
     * predicate. Coarser than about render zoom 10 lands there, where no index helps anyway
     * because the box already contains everything.
     */
    fun covering(south: Double, north: Double): IntArray? {
        val first = of(minOf(south, north))
        val last = of(maxOf(south, north))
        val count = last - first + 1
        if (count > MAX_BUCKETS) return null
        return IntArray(MAX_BUCKETS) { index ->
            if (index < count) first + index else PADDING_BUCKET
        }
    }
}
