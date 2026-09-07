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
    fun around(latitude: Double, longitude: Double): List<DemoTrackPoint> {
        val anchorLatitude = latitude.coerceIn(MIN_ANCHOR_LATITUDE, MAX_ANCHOR_LATITUDE)
        val metresPerDegreeLongitude =
            METRES_PER_DEGREE_LATITUDE * cos(Math.toRadians(anchorLatitude))
        val anchorLongitude = WebMercator.wrapLongitude(longitude)

        return offsets().map { (east, north) ->
            val pointLatitude =
                (anchorLatitude + north / METRES_PER_DEGREE_LATITUDE).coerceIn(-85.0, 85.0)
            val pointLongitude =
                WebMercator.wrapLongitude(anchorLongitude + east / metresPerDegreeLongitude)
            DemoTrackPoint(pointLatitude, pointLongitude)
        }
    }

    /** Metres east and north of the anchor. Split out so the shape can be reasoned about flat. */
    fun offsets(): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>(
            CORRIDOR_POINTS + LOOP_POINTS + ISLAND_POINTS,
        )

        // The corridor: a long leg with one and a half waves across it, so the boundary presents
        // every angle rather than the two an axis-aligned shape would.
        for (index in 0 until CORRIDOR_POINTS) {
            val t = index.toDouble() / (CORRIDOR_POINTS - 1)
            points += Pair(
                CORRIDOR_WEST_METRES + CORRIDOR_LENGTH_METRES * t,
                CORRIDOR_AMPLITUDE_METRES * sin(TAU * CORRIDOR_WAVES * t),
            )
        }

        // The loop, closed and deliberately overlapping the corridor's east end.
        for (index in 0 until LOOP_POINTS) {
            val angle = TAU * index.toDouble() / LOOP_POINTS
            points += Pair(
                LOOP_CENTRE_EAST_METRES + LOOP_RADIUS_METRES * cos(angle),
                LOOP_CENTRE_NORTH_METRES + LOOP_RADIUS_METRES * sin(angle),
            )
        }

        // The island: far enough south-west that its reveal cannot touch the corridor's.
        for (index in 0 until ISLAND_POINTS) {
            val t = index.toDouble() / (ISLAND_POINTS - 1)
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
    fun lengthMetres(): Double {
        val offsets = offsets()
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

    /** How far apart consecutive points are along the corridor, for the spacing assertion. */
    const val CORRIDOR_POINTS = 320
    const val LOOP_POINTS = 200
    const val ISLAND_POINTS = 90

    const val TOTAL_POINTS = CORRIDOR_POINTS + LOOP_POINTS + ISLAND_POINTS

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

    internal fun separationFromIsland(): Double {
        val all = offsets()
        val island = all.takeLast(ISLAND_POINTS)
        val rest = all.dropLast(ISLAND_POINTS)
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
