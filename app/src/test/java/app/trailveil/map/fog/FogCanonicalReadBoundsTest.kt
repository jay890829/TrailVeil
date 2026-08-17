package app.trailveil.map.fog

import app.trailveil.data.map.LongitudeInterval
import app.trailveil.data.map.ViewportBounds
import app.trailveil.data.map.ViewportTrackDataSource
import app.trailveil.data.map.ViewportTrackPoint
import app.trailveil.data.map.ViewportTrackPointReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a settle costs the canonical store.
 *
 * The tile window at render zoom 0-1 is the whole world, so a coordinator that reads for every
 * tile it is about to compose reads the entire track table every time the user zooms out - which a
 * forty-point test database cannot show and a real one pays for. These gates are written against a
 * populated dataset spread across the world so that a world read and a bounded read produce
 * visibly different numbers, rather than being argued from the shape of the code.
 */
class FogCanonicalReadBoundsTest {
    /** Records every interval it is asked for and answers from a world-spanning point set. */
    private class RecordingReader(points: List<ViewportTrackPoint>) : ViewportTrackPointReader {
        private val all = points
        val reads = mutableListOf<Triple<Double, Double, LongitudeInterval>>()
        var pointsReturned = 0
            private set

        override suspend fun read(
            south: Double,
            north: Double,
            interval: LongitudeInterval,
        ): List<ViewportTrackPoint> {
            reads += Triple(south, north, interval)
            val matched = all.filter { point ->
                point.latitude in south..north &&
                    point.longitude >= interval.west &&
                    point.longitude <= interval.east
            }
            pointsReturned += matched.size
            return matched
        }
    }

    private fun worldWidePoints(): List<ViewportTrackPoint> {
        // One point every degree of longitude at three latitudes: dense enough that a world read
        // returns two orders of magnitude more than an exploration-zoom window, and regular enough
        // that the expected count for a bounded read is arithmetic rather than a magic number.
        var id = 1L
        return buildList {
            listOf(-40.0, 0.0, 40.0).forEach { latitude ->
                var longitude = -179.0
                while (longitude <= 179.0) {
                    add(
                        ViewportTrackPoint(
                            pointId = id,
                            sessionId = 1L,
                            segmentId = 1L,
                            segmentSequence = 0L,
                            pointSequence = id,
                            latitude = latitude,
                            longitude = longitude,
                        ),
                    )
                    id += 1
                    longitude += 1.0
                }
            }
        }
    }

    private fun coordinator(reader: RecordingReader): FogViewportCoordinator =
        FogViewportCoordinator(
            trackDataSource = ViewportTrackDataSource(reader),
            pipeline = FogTilePipeline(
                memoryCache = FogMemoryTileCache(maxBytes = 64L * 1024 * 1024),
                diskCache = null,
                renderMask = { key, segments -> FogTileRenderer(FogRenderStyle()).render(key, segments) },
            ),
        )

    @Test
    fun aSettleWhoseTilesAreAllCachedReadsNothing() = runTest {
        val reader = RecordingReader(worldWidePoints())
        val coordinator = coordinator(reader)
        val request = FogViewportRequest(
            center = GeoPoint(latitude = 25.0330, longitude = 121.5654),
            mapZoom = 16.0,
        )

        val cold = coordinator.render(request)
        assertNotNull("the first settle must read; it has nothing cached", cold.queryBounds)
        val readsAfterCold = reader.reads.size
        assertTrue("the cold settle issued no read at all", readsAfterCold > 0)

        val warm = coordinator.render(request)

        assertNull("a settle whose tiles are all cached read canonical storage", warm.queryBounds)
        assertEquals(
            "a settle whose tiles are all cached issued a canonical query",
            readsAfterCold,
            reader.reads.size,
        )
        // Deliberately not asserting mosaic bounds here: they are a pure function of the request,
        // so that comparison cannot fail for any read path. What the narrowing could break is the
        // PIXELS, and that is asserted against a cold reference in the equivalence gate below.
    }

    @Test
    fun aRepeatedWorldZoomSettleReadsNothingEvenThoughItsWindowIsTheWholeWorld() = runTest {
        val reader = RecordingReader(worldWidePoints())
        val coordinator = coordinator(reader)
        val request = FogViewportRequest(
            center = GeoPoint(latitude = 0.0, longitude = 0.0),
            mapZoom = 0.0,
        )

        val cold = coordinator.render(request)
        val worldRead = requireNotNull(cold.queryBounds)
        assertEquals(
            "the cold world settle should span the world, which is the cost being bounded",
            listOf(-180.0 to 180.0),
            worldRead.longitudeIntervals().map { interval -> interval.west to interval.east },
        )
        val pointsAfterCold = reader.pointsReturned

        repeat(5) { coordinator.render(request) }

        assertEquals(
            "zooming all the way out repeatedly kept re-reading the whole track table",
            pointsAfterCold,
            reader.pointsReturned,
        )
    }

    @Test
    fun aColdExplorationSettleReadsItsOwnNeighbourhoodRatherThanTheWorld() = runTest {
        val reader = RecordingReader(worldWidePoints())
        val coordinator = coordinator(reader)

        coordinator.render(
            FogViewportRequest(
                center = GeoPoint(latitude = 0.0, longitude = 0.0),
                mapZoom = 16.0,
            ),
        )

        val bounds = reader.reads.single().let { (south, north, interval) ->
            ViewportBounds(south = south, north = north, west = interval.west, east = interval.east)
        }
        // Three tiles across at zoom 16 is a few hundred metres of ground, so the 6.1 km margin
        // dominates and the box is about a tenth of a degree. Asserted near its true size rather
        // than "under a degree", which an eight-fold regression would still satisfy.
        assertEquals(
            "the exploration settle read a longitude span this wide",
            0.124,
            bounds.east - bounds.west,
            0.02,
        )
        assertEquals(
            "the exploration settle read a latitude span this tall",
            0.115,
            bounds.north - bounds.south,
            0.02,
        )
        assertEquals(
            "the exploration settle pulled this many points from a world-wide table",
            1,
            reader.pointsReturned,
        )
    }

    @Test
    fun onlyTheMissingTilesAreReadForWhenTheRestAreCached() = runTest {
        val reader = RecordingReader(worldWidePoints())
        val coordinator = coordinator(reader)
        // Two overlapping windows one tile apart: the second settle shares all but one column with
        // the first, so all but that column is already cached.
        val first = FogViewportRequest(
            center = GeoPoint(latitude = 0.0, longitude = 0.0),
            mapZoom = 4.0,
        )
        coordinator.render(first)
        val coldBounds = reader.reads.last().let { (south, north, interval) ->
            ViewportBounds(south = south, north = north, west = interval.west, east = interval.east)
        }
        val readsAfterFirst = reader.reads.size

        // One tile east at zoom 4 is 22.5 degrees of longitude.
        val second = FogViewportRequest(
            center = GeoPoint(latitude = 0.0, longitude = 22.5),
            mapZoom = 4.0,
        )
        val warm = coordinator.render(second)

        assertNotNull("the shifted window has an uncached column and must read", warm.queryBounds)
        assertTrue("the shifted settle issued no read", reader.reads.size > readsAfterFirst)
        val narrowed = requireNotNull(warm.queryBounds)
        // One uncached column at zoom 4 is 22.5 degrees wide plus the query margin on each side;
        // the cold window was three columns. Asserted as a number so a regression that quietly
        // reads the whole window again says so in the failure rather than passing a comparison.
        val columnDegrees = 22.5
        val marginDegrees = (coldBounds.east - coldBounds.west - 3 * columnDegrees) / 2.0
        assertEquals(
            "the shifted settle read a window this wide instead of its one uncached column",
            columnDegrees + 2 * marginDegrees,
            narrowed.east - narrowed.west,
            0.001,
        )
    }

    /**
     * The gate the narrowing actually needs: a tile rendered from a read of its own sub-rectangle
     * must come out identical to the same tile rendered from a cold read of the whole window.
     *
     * A narrowing that reads too small a box does not fail loudly - it draws MORE fog, which every
     * leak audit accepts and every settled sweep passes, and the wrong mask is then cached to
     * memory and disk. Only a comparison against a cold reference can see it. Zoom 16 because the
     * reveal radius is 25 m and a mask pixel is about 2.4 m there; at low zooms every mask is
     * uniformly opaque and this comparison would be vacuous. The track is spaced inside the
     * accepted 6 km continuity ceiling that the query margin is sized for, and crosses a tile
     * boundary so the shifted window's uncached column has capsules reaching into it.
     */
    @Test
    fun aPartiallyCachedSettleDrawsTheSameFogAsAColdOne() = runTest {
        val trackPoints = buildList {
            var id = 1L
            var longitude = 121.560
            // Past 121.5747, where the shifted window's newly read column begins: a track that
            // stops short of it leaves that column uniformly opaque in both renders, and the
            // comparison then binds composition identity rather than read-box sizing. Float
            // accumulation made an earlier <= 121.575 bound stop 71 m short of exactly that.
            while (longitude <= 121.5800) {
                add(
                    ViewportTrackPoint(
                        pointId = id,
                        sessionId = 1L,
                        segmentId = 1L,
                        segmentSequence = 0L,
                        pointSequence = id,
                        latitude = 25.0330,
                        longitude = longitude,
                    ),
                )
                id += 1
                // About 100 m apart, far inside the accepted continuity ceiling.
                longitude += 0.001
            }
        }
        val warmRequest = FogViewportRequest(
            center = GeoPoint(latitude = 25.0330, longitude = 121.5700),
            mapZoom = 16.0,
        )

        val incremental = coordinator(RecordingReader(trackPoints))
        // Settle next door first so the shifted window below is partly cached and partly not.
        incremental.render(
            FogViewportRequest(
                center = GeoPoint(latitude = 25.0330, longitude = 121.5654),
                mapZoom = 16.0,
            ),
        )
        val partiallyCached = incremental.render(warmRequest)

        val cold = coordinator(RecordingReader(trackPoints)).render(warmRequest)

        assertEquals(
            "the two settles did not even compose the same tiles",
            cold.keys,
            partiallyCached.keys,
        )
        val reference = cold.mosaic.mask
        val measured = partiallyCached.mosaic.mask
        assertEquals(reference.width, measured.width)
        assertEquals(reference.height, measured.height)
        var firstDifference: String? = null
        var differing = 0
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                if (reference.alphaAt(x, y) != measured.alphaAt(x, y)) {
                    differing += 1
                    if (firstDifference == null) {
                        firstDifference = "($x,$y) cold=${reference.alphaAt(x, y)} " +
                            "partial=${measured.alphaAt(x, y)}"
                    }
                }
            }
        }
        assertEquals(
            "a partially cached settle drew different fog from a cold one at $differing pixels, " +
                "first at $firstDifference",
            0,
            differing,
        )
        // Non-vacuity: the reference must actually contain revealed ground, or two blank masks
        // would agree and prove nothing.
        var revealed = 0
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                if (reference.alphaAt(x, y) < 255) revealed += 1
            }
        }
        assertTrue("the cold reference revealed nothing, so this compared two blank masks", revealed > 0)
    }

    @Test
    fun theEnclosingSubWindowIsTheSmallestCompleteRectangleAroundWhatIsMissing() {
        val window = FogViewportTileGrid.around(
            center = GeoPoint(latitude = 0.0, longitude = 0.0),
            zoom = 4,
            renderVersion = 1,
        )
        val corner = window.last()

        val single = FogViewportTileGrid.enclosingSubWindow(window, setOf(corner))
        assertEquals(listOf(corner), single)

        val column = window.filter { key -> key.x == corner.x }
        assertEquals(column, FogViewportTileGrid.enclosingSubWindow(window, column.toSet()))
        assertEquals(
            "a subset spanning opposite corners must expand to the whole window",
            window,
            FogViewportTileGrid.enclosingSubWindow(window, setOf(window.first(), window.last())),
        )
    }

    @Test
    fun theEnclosingSubWindowKeepsColumnsConsecutiveAcrossTheAntimeridian() {
        val window = FogViewportTileGrid.around(
            center = GeoPoint(latitude = 0.0, longitude = 179.9),
            zoom = 4,
            renderVersion = 1,
        )
        // Columns 14, 15, 0: their numbers wrap, their order does not.
        val wrapped = setOf(window[1], window[2])

        val sub = FogViewportTileGrid.enclosingSubWindow(window, wrapped)

        assertEquals(listOf(15, 0), sub.take(2).map(FogTileKey::x))
        // The whole point of narrowing is that the result is still a legal query rectangle.
        val bounds = FogViewportTileGrid.queryBounds(sub, marginMeters = 6_100.0)
        assertTrue("a wrapped narrowing must still split into storage intervals", bounds.west > bounds.east)
    }
}
