package io.github.jay890829.trailveil.map.fog

object SyntheticFogDatasets {
    const val TYPICAL_POINT_COUNT = 10_000
    const val STRESS_POINT_COUNT = 100_000
    const val STRESS_SEGMENT_COUNT = 500

    fun typical10k(): List<TrackSegment> =
        generate(
            pointCount = TYPICAL_POINT_COUNT,
            segmentCount = 100,
            seed = 0x545241494C564549L,
        )

    fun stress100k(): List<TrackSegment> =
        generate(
            pointCount = STRESS_POINT_COUNT,
            segmentCount = STRESS_SEGMENT_COUNT,
            seed = 0x5354524553533130L,
        )

    /**
     * Small deterministic fixture for geometry failure modes that random walks cannot guarantee.
     */
    fun edgeCases(): List<TrackSegment> = listOf(
        TrackSegment(
            id = 0,
            points = listOf(GeoPoint(0.0, 179.75), GeoPoint(0.0, -179.75)),
        ),
        TrackSegment(
            id = 1,
            points = listOf(GeoPoint(0.001, -0.001), GeoPoint(-0.001, 0.001)),
        ),
        TrackSegment(id = 2, points = listOf(GeoPoint(WebMercator.MAX_LATITUDE, 0.0))),
        TrackSegment(id = 3, points = listOf(GeoPoint(-WebMercator.MAX_LATITUDE, 0.0))),
        TrackSegment(id = 4, points = listOf(GeoPoint(35.0, -120.0))),
        TrackSegment(id = 5, points = listOf(GeoPoint(-35.0, 120.0))),
        TrackSegment(
            id = 6,
            points = listOf(GeoPoint(10.0, 10.0), GeoPoint(10.0, 10.0)),
        ),
        TrackSegment(
            id = 7,
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 180.0)),
        ),
    )

    internal fun generate(        pointCount: Int,
        segmentCount: Int,
        seed: Long,
    ): List<TrackSegment> {
        require(pointCount >= 0) { "pointCount must not be negative" }
        require(segmentCount > 0 || pointCount == 0) {
            "segmentCount must be positive when points are requested"
        }
        if (pointCount == 0) return emptyList()
        require(segmentCount <= pointCount) { "segmentCount must not exceed pointCount" }

        val random = SplitMix64(seed)
        val baseSize = pointCount / segmentCount
        val remainder = pointCount % segmentCount
        return List(segmentCount) { segmentIndex ->
            val size = baseSize + if (segmentIndex < remainder) 1 else 0
            var latitude = -65.0 + random.nextUnitDouble() * 130.0
            var longitude = -180.0 + random.nextUnitDouble() * 360.0
            val points = ArrayList<GeoPoint>(size)
            repeat(size) {
                points += GeoPoint(latitude, WebMercator.wrapLongitude(longitude))
                latitude = (latitude + (random.nextUnitDouble() - 0.5) * 0.002)
                    .coerceIn(-80.0, 80.0)
                longitude = WebMercator.wrapLongitude(
                    longitude + (random.nextUnitDouble() - 0.5) * 0.003,
                )
            }
            TrackSegment(segmentIndex, points)
        }
    }

    private class SplitMix64(seed: Long) {
        private var state = seed

        fun nextUnitDouble(): Double {
            state += -7046029254386353131L
            var value = state
            value = (value xor (value ushr 30)) * -4658895280553007687L
            value = (value xor (value ushr 27)) * -7723592293110705685L
            value = value xor (value ushr 31)
            return (value ushr 11).toDouble() / (1L shl 53).toDouble()
        }
    }
}
