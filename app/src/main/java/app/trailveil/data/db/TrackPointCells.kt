package app.trailveil.data.db

import kotlin.math.floor

/**
 * A coarse `(latitude, longitude)` cell, so the world-zoom cold read stops reading the world.
 *
 * `P4-037`. At render zoom 0-1 the tile window IS the world, so the first settle out there visits
 * every row in `track_points` however narrow the camera's interest. [LatitudeBuckets] cannot help:
 * it narrows a BAND, and at world zoom the band is the planet. This narrows the other way — one row
 * per occupied cell instead of one row per point, maintained by the `track_points_cell_insert`
 * trigger so that every write route is covered, not only the DAO's.
 *
 * **The shortcut this replaces was refuted by measurement, not by taste.** Aggregating points with
 * `GROUP BY` instead of materialising them returns few rows and saves almost nothing, because the
 * cost of a world settle is VISITING rows, not returning them, and an aggregate still visits every
 * one. Only materialising removes the visit. The host measurements behind that conclusion are in
 * the ledger and are deliberately NOT restated here: their two baseline timings for the same read
 * disagree with each other, so quoting them in source would propagate a discrepancy this file
 * cannot resolve.
 *
 * The narrowing actually measured for this change is **13.3x** on the read-cost fixture (40 000
 * points to 3 000 cells) and **22.8x** on a copy of the real database (2 650 points to 116 cells).
 * The fixture is the pessimistic case; a walked track shares cells far more densely.
 *
 * The granularity is [LatitudeBuckets.BUCKETS_PER_DEGREE] on both axes, reused rather than restated
 * so retuning it cannot silently desynchronise the two.
 */
internal object TrackPointCells {

    /** Cells per degree on both axes — about 222 m of latitude, and less than that of longitude. */
    const val CELLS_PER_DEGREE = LatitudeBuckets.BUCKETS_PER_DEGREE

    /**
     * The same granularity spelled for SQL, because Room needs a compile-time constant in `@Query`.
     *
     * Written as a literal string rather than interpolated so it IS constant, and pinned to
     * [CELLS_PER_DEGREE] by a test rather than by this comment — that is exactly the tether
     * `P4-036` found insufficient between its own two constants.
     */
    const val CELLS_PER_DEGREE_SQL = "500.0"

    /** The cell a point's latitude falls in, as SQL sees it, for `NEW.`-qualified trigger use. */
    const val LAT_CELL_SQL = "CAST((NEW.latitude + 90.0) * $CELLS_PER_DEGREE_SQL AS INTEGER)"

    /** The cell a point's longitude falls in, as SQL sees it, for `NEW.`-qualified trigger use. */
    const val LON_CELL_SQL = "CAST((NEW.longitude + 180.0) * $CELLS_PER_DEGREE_SQL AS INTEGER)"

    /**
     * Rebuilds every cell from the points currently stored; one spelling for the migration backfill
     * and for the repair after a delete, which would otherwise be the same SQL written twice.
     *
     * `CAST(... AS INTEGER)` truncates toward zero where [longitudeCellOf] floors. They agree for
     * every valid coordinate because both operands are shifted non-negative first, and that is the
     * same equivalence the `lat_bucket` trigger has always rested on rather than a new assumption.
     * `TrackPointCellDerivationTest` evaluates the stored SQL against the Kotlin, so it is measured.
     */
    const val BACKFILL_SQL =
        "INSERT OR IGNORE INTO track_point_cells(lat_cell, lon_cell) SELECT DISTINCT " +
            "CAST((latitude + 90.0) * " + CELLS_PER_DEGREE_SQL + " AS INTEGER), " +
            "CAST((longitude + 180.0) * " + CELLS_PER_DEGREE_SQL + " AS INTEGER) FROM track_points"

    /**
     * How many times smaller than one mask pixel a cell must be before it may stand in for points.
     *
     * A cell substitutes a single centre for every point inside it, so the question is only whether
     * that displacement can move a rasterised pixel. This is the margin demanded of that answer, and
     * [MAX_RENDER_ZOOM] is DERIVED from it rather than chosen beside it — the two would otherwise be
     * tethered by a comment, which is how [LatitudeBuckets.MAX_BUCKETS] previously drifted.
     */
    const val MINIMUM_CELLS_PER_MASK_PIXEL = 256.0

    /** Mask raster width at zoom 0, in pixels. The world doubles with each further zoom level. */
    const val MASK_TILE_SIZE_PIXELS = 256.0

    /**
     * The coarsest-resolution zooms at which a cell is provably sub-pixel, and so the only zooms
     * where the cell route may be used.
     *
     * Derived: at render zoom `z` the world is `256 * 2^z` mask pixels wide, so one pixel spans
     * `360 / (256 * 2^z)` degrees, while a cell spans `1 / CELLS_PER_DEGREE`. Requiring at least
     * [MINIMUM_CELLS_PER_MASK_PIXEL] cells to a pixel gives `703.125 / 2^z >= 256`, so `z <= 1`.
     * Concretely: at zoom 0 a mask pixel is 1.40625 degrees, about 156 km at the equator, against a
     * 222 m cell — roughly 1/700 of a pixel; at zoom 1 it is 1/350.
     *
     * Everywhere finer than this reads points, and the exact predicates decide membership as they
     * always did. The cell route narrows a read that has no other bound; it never decides an answer
     * at a zoom where the difference could be seen.
     */
    val MAX_RENDER_ZOOM: Int = run {
        var zoom = 0
        while (cellsPerMaskPixel(zoom + 1) >= MINIMUM_CELLS_PER_MASK_PIXEL) zoom++
        zoom
    }

    /** How many cells span one mask pixel at [renderZoom]; the derivation input for [MAX_RENDER_ZOOM]. */
    fun cellsPerMaskPixel(renderZoom: Int): Double =
        FULL_TURN_DEGREES / (MASK_TILE_SIZE_PIXELS * (1 shl renderZoom)) * CELLS_PER_DEGREE

    /** True when a cell is small enough at [renderZoom] that substituting its centre cannot be drawn. */
    fun coarseReadIsSubPixel(renderZoom: Int): Boolean = renderZoom <= MAX_RENDER_ZOOM

    fun latitudeCellOf(latitude: Double): Int = LatitudeBuckets.of(latitude)

    fun longitudeCellOf(longitude: Double): Int =
        floor((longitude + HALF_TURN_DEGREES) * CELLS_PER_DEGREE).toInt()

    /**
     * The centre of a cell, which is what stands in for every point inside it.
     *
     * The centre rather than a corner so the worst displacement is half a cell rather than a whole
     * one, and so the substitution is symmetric — a corner would bias every revealed dot the same
     * way, which is the kind of systematic error a sub-pixel argument stops covering first.
     */
    fun latitudeAtCentreOf(latitudeCell: Int): Double =
        (latitudeCell + CELL_CENTRE) / CELLS_PER_DEGREE - QUARTER_TURN_DEGREES

    fun longitudeAtCentreOf(longitudeCell: Int): Double =
        (longitudeCell + CELL_CENTRE) / CELLS_PER_DEGREE - HALF_TURN_DEGREES

    private const val FULL_TURN_DEGREES = 360.0
    private const val HALF_TURN_DEGREES = 180.0

    /**
     * The latitude shift, which is NOT the longitude one.
     *
     * Spelled separately rather than as `HALF_TURN_DEGREES / 2.0`, which is a puzzle in a file whose
     * subject is arithmetic. It has to agree with the `+ 90.0` inside [LatitudeBuckets.of], since
     * [latitudeCellOf] delegates there while this function inverts it by hand; that agreement is
     * measured by the centre round-trip case rather than trusted to these two lines being read
     * together.
     */
    private const val QUARTER_TURN_DEGREES = 90.0
    private const val CELL_CENTRE = 0.5
}
