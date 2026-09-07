package app.trailveil.harness

import app.trailveil.map.fog.WebMercator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One synthetic point, in the only two terms the seeder needs. */
internal data class DemoTrackPoint(val latitude: Double, val longitude: Double)

/**
 * `V03-013`: the shape of the demo exploration, as pure arithmetic.
 *
 * The arms are compared by looking at a fog BOUNDARY, and a fog boundary needs revealed ground to
 * be a boundary of. Walking to make some is the honest way and is not available on demand, so this
 * draws a walk instead - and what it draws is chosen to put the three things the arms differ about
 * on one screen:
 *
 * 1. **A curved corridor**, because the staircase section 15p predicts is only visible on a
 *    boundary that is not axis-aligned. A straight line would flatter every arm equally.
 * 2. **A closed loop that crosses itself**, because overlapping reveal circles are exactly where a
 *    hole-based surface can go wrong - a ring inside another ring re-fills what it was meant to
 *    remove - and `FogMaskContours` claims that cannot happen. This is that claim, on screen.
 * 3. **A detached island**, because ring COUNT is the vector arms' real cost, and a second
 *    component is what makes the count more than one.
 *
 * **It is deterministic, and that is a requirement rather than a nicety.** Arms are compared by
 * switching arm and looking again; if the track were random, every switch would also change the
 * subject. No clock, no random source, no device state: the same anchor always yields the same
 * walk, so the only thing that differs between two screenshots is the arm.
 *
 * Distances are metres on a local tangent plane. Over the ~2 km this spans, that is right to well
 * under a metre, which is far inside the 25 m reveal radius it feeds.
 */
internal object DemoExplorationTrack {

    /**
     * The walk, anchored anywhere on Earth a person can stand.
     *
     * The anchor is clamped rather than rejected: a latitude near the pole makes the
     * metres-per-degree-of-longitude term collapse, and the failure that produces is a track
     * smeared around the world rather than an exception anyone would see.
     */
    fun around(
        latitude: Double,
        longitude: Double,
        pointCount: Int = DEFAULT_POINTS,
    ): List<DemoTrackPoint> {
        val anchorLatitude = latitude.coerceIn(MIN_ANCHOR_LATITUDE, MAX_ANCHOR_LATITUDE)
        val metresPerDegreeLongitude =
            METRES_PER_DEGREE_LATITUDE * cos(Math.toRadians(anchorLatitude))
        val anchorLongitude = WebMercator.wrapLongitude(longitude)

        return offsets(pointCount).map { (east, north) ->
            val pointLatitude =
                (anchorLatitude + north / METRES_PER_DEGREE_LATITUDE).coerceIn(-85.0, 85.0)
            val pointLongitude =
                WebMercator.wrapLongitude(anchorLongitude + east / metresPerDegreeLongitude)
            DemoTrackPoint(pointLatitude, pointLongitude)
        }
    }

    /**
     * The walk as its three SEPARATE components, which is how it must be stored.
     *
     * The fog does not reveal points, it reveals the capsule swept between consecutive points in a
     * SEGMENT. Storing all three components as one segment therefore draws two revealed corridors
     * along the 700 m jumps between them - so the "detached island" is not detached, and the
     * comparison fixture quietly contains two long straight reveals nobody designed. Measured on the
     * emulator, those two bands are most of the 118,023 pixels the screen-stencil arm was missing
     * against the tile path, because that arm draws circles at points and no capsule between them.
     *
     * Three segments is also what a real walk with two breaks in it looks like, so this is the
     * honest shape rather than a workaround.
     */
    fun components(pointCount: Int = DEFAULT_POINTS): List<List<Pair<Double, Double>>> {
        val all = offsets(pointCount)
        val corridorPoints = pointCount * CORRIDOR_POINTS / DEFAULT_POINTS
        val loopPoints = pointCount * LOOP_POINTS / DEFAULT_POINTS
        return listOf(
            all.subList(0, corridorPoints),
            all.subList(corridorPoints, corridorPoints + loopPoints),
            all.subList(corridorPoints + loopPoints, all.size),
        )
    }

    /** The same three components anchored on Earth, one list per segment. */
    fun componentsAround(
        latitude: Double,
        longitude: Double,
        pointCount: Int = DEFAULT_POINTS,
    ): List<List<DemoTrackPoint>> {
        val flat = around(latitude, longitude, pointCount)
        val corridorPoints = pointCount * CORRIDOR_POINTS / DEFAULT_POINTS
        val loopPoints = pointCount * LOOP_POINTS / DEFAULT_POINTS
        return listOf(
            flat.subList(0, corridorPoints),
            flat.subList(corridorPoints, corridorPoints + loopPoints),
            flat.subList(corridorPoints + loopPoints, flat.size),
        )
    }

    /**
     * Metres east and north of the anchor. Split out so the shape can be reasoned about flat.
     *
     * **The point count changes the SAMPLING, never the shape.** The three components keep the same
     * share of the total at every density, so a 1024-point session and a 610-point one trace the
     * same corridor, the same loop and the same island - one just walks it with shorter strides.
     * That is what lets a dense load fixture and a sparse comparison fixture be looked at as the
     * same walk.
     */
    fun offsets(pointCount: Int = DEFAULT_POINTS): List<Pair<Double, Double>> {
        require(pointCount >= MIN_POINTS) { "pointCount must be at least $MIN_POINTS" }
        val corridorPoints = pointCount * CORRIDOR_POINTS / DEFAULT_POINTS
        val loopPoints = pointCount * LOOP_POINTS / DEFAULT_POINTS
        val islandPoints = pointCount - corridorPoints - loopPoints
        val points = ArrayList<Pair<Double, Double>>(pointCount)

        // The corridor: a long leg with one and a half waves across it, so the boundary presents
        // every angle rather than the two an axis-aligned shape would.
        for (index in 0 until corridorPoints) {
            val t = index.toDouble() / (corridorPoints - 1)
            points += Pair(
                CORRIDOR_WEST_METRES + CORRIDOR_LENGTH_METRES * t,
                CORRIDOR_AMPLITUDE_METRES * sin(TAU * CORRIDOR_WAVES * t),
            )
        }

        // The loop, closed and deliberately overlapping the corridor's east end.
        for (index in 0 until loopPoints) {
            val angle = TAU * index.toDouble() / loopPoints
            points += Pair(
                LOOP_CENTRE_EAST_METRES + LOOP_RADIUS_METRES * cos(angle),
                LOOP_CENTRE_NORTH_METRES + LOOP_RADIUS_METRES * sin(angle),
            )
        }

        // The island: far enough south-west that its reveal cannot touch the corridor's.
        for (index in 0 until islandPoints) {
            val t = index.toDouble() / (islandPoints - 1)
            points += Pair(
                ISLAND_CENTRE_EAST_METRES + ISLAND_LENGTH_METRES * t,
                ISLAND_CENTRE_NORTH_METRES + ISLAND_AMPLITUDE_METRES * sin(TAU * ISLAND_WAVES * t),
            )
        }
        return points
    }

    /**
     * The walk's length along the ground, so the seeded session can report a distance that matches
     * what it drew instead of a zero the history screen would show as an empty exploration.
     */
    fun lengthMetres(pointCount: Int = DEFAULT_POINTS): Double {
        val offsets = offsets(pointCount)
        var total = 0.0
        for (index in 1 until offsets.size) {
            val (east, north) = offsets[index]
            val (previousEast, previousNorth) = offsets[index - 1]
            val deltaEast = east - previousEast
            val deltaNorth = north - previousNorth
            val step = kotlin.math.sqrt(deltaEast * deltaEast + deltaNorth * deltaNorth)
            // The jump from the corridor's end to the loop, and from the loop to the island, are
            // not walked distance. A real break would be two segments; this stays one segment and
            // simply declines to count the teleport.
            if (step <= MAX_WALKED_STEP_METRES) total += step
        }
        return total
    }

    /** The reference split, and the denominator every other density is scaled against. */
    const val CORRIDOR_POINTS = 320
    const val LOOP_POINTS = 200
    const val ISLAND_POINTS = 90

    const val DEFAULT_POINTS = CORRIDOR_POINTS + LOOP_POINTS + ISLAND_POINTS

    /** Below this the island cannot hold two points and the loop stops closing. */
    const val MIN_POINTS = 60

    /**
     * A step longer than this is a jump between components rather than a stride.
     *
     * Set well above the ~6 m the shape actually steps and well below the ~700 m between
     * components, so it separates the two without being tuned to either.
     */
    const val MAX_WALKED_STEP_METRES = 60.0

    private const val TAU = 2.0 * Math.PI
    private const val METRES_PER_DEGREE_LATITUDE = 111_320.0

    /**
     * Beyond this the longitude term collapses and the track smears around the world.
     *
     * At 80 degrees a degree of longitude is still about 19 km, so the ~1.5 km this spans is
     * nowhere near degenerate.
     */
    private const val MAX_ANCHOR_LATITUDE = 80.0
    private const val MIN_ANCHOR_LATITUDE = -80.0

    private const val CORRIDOR_WEST_METRES = -700.0
    private const val CORRIDOR_LENGTH_METRES = 1400.0
    private const val CORRIDOR_AMPLITUDE_METRES = 260.0
    private const val CORRIDOR_WAVES = 1.5

    private const val LOOP_CENTRE_EAST_METRES = 760.0
    private const val LOOP_CENTRE_NORTH_METRES = 180.0
    private const val LOOP_RADIUS_METRES = 170.0

    private const val ISLAND_CENTRE_EAST_METRES = -620.0
    private const val ISLAND_CENTRE_NORTH_METRES = -560.0
    private const val ISLAND_LENGTH_METRES = 240.0
    private const val ISLAND_AMPLITUDE_METRES = 70.0
    private const val ISLAND_WAVES = 3.0

    /** The gap the island must keep from everything else, so it stays a separate component. */
    const val MIN_ISLAND_SEPARATION_METRES = 300.0

    internal fun separationFromIsland(pointCount: Int = DEFAULT_POINTS): Double {
        val all = offsets(pointCount)
        val islandPoints = pointCount -
            pointCount * CORRIDOR_POINTS / DEFAULT_POINTS -
            pointCount * LOOP_POINTS / DEFAULT_POINTS
        val island = all.takeLast(islandPoints)
        val rest = all.dropLast(islandPoints)
        var closest = Double.MAX_VALUE
        island.forEach { (islandEast, islandNorth) ->
            rest.forEach { (east, north) ->
                val deltaEast = abs(east - islandEast)
                val deltaNorth = abs(north - islandNorth)
                val distance =
                    kotlin.math.sqrt(deltaEast * deltaEast + deltaNorth * deltaNorth)
                if (distance < closest) closest = distance
            }
        }
        return closest
    }
}
