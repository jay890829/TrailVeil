package app.trailveil.map.fog

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * `V03-013` opt-in JVM timing of the low-zoom raster stages (set `TRAILVEIL_FOG_BENCH=1`). It
 * prints, never asserts a duration: the device numbers are the evidence, this only shows the
 * relative shape on a desktop JVM. The reference implementations are the ones in
 * [FogRasterEquivalenceTest]; here only the current code is timed.
 */
class FogLowZoomRasterBenchTest {
    @Test
    fun printLowZoomRasterStageTimings() {
        assumeTrue("opt-in: TRAILVEIL_FOG_BENCH=1", System.getenv("TRAILVEIL_FOG_BENCH") == "1")
        val style = FogRenderStyle()
        val renderer = FogTileRenderer(style)
        val datasets = listOf(
            "stress100k" to SyntheticFogDatasets.stress100k(),
            "denseWalks204k" to denseWalks(),
        )
        datasets.forEach { (label, segments) ->
            listOf(8, 10, 12).forEach { zoom ->
                val keys = FogViewportTileGrid.around(GeoPoint(23.6, 121.0), zoom, 1, paddingTiles = 1)
                repeat(3) { round ->
                    val selectStarted = System.nanoTime()
                    val selected = FogPocSpatialSelection.select(keys, segments, style)
                    val selectMillis = (System.nanoTime() - selectStarted) / 1_000_000
                    val renderStarted = System.nanoTime()
                    keys.forEach { key -> renderer.render(key, selected[key].orEmpty()) }
                    val renderMillis = (System.nanoTime() - renderStarted) / 1_000_000
                    val perTile = keys.sumOf { key -> selected[key].orEmpty().sumOf { it.points.size } }
                    println(
                        "FOG-BENCH $label zoom=$zoom round=$round keys=${keys.size} " +
                            "pointDraws=$perTile selectMs=$selectMillis renderMs=$renderMillis",
                    )
                }
            }
        }
    }

    /** The Taiwan fixture's shape: 200 walks of 1,024 points at ~2 m steps, scattered over a 3.4 x 2 degree box. */
    private fun denseWalks(): List<TrackSegment> {
        var state = 0x5EEDL
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 11).toDouble()) / (1L shl 53).toDouble()
        }
        return List(200) { index ->
            var latitude = 21.9 + next() * 3.4
            var longitude = 120.0 + next() * 2.0
            val points = ArrayList<GeoPoint>(1_024)
            repeat(1_024) {
                points += GeoPoint(latitude, longitude)
                latitude += (next() - 0.5) * 4e-5
                longitude += (next() - 0.5) * 4e-5
            }
            TrackSegment(index, points)
        }
    }
}
