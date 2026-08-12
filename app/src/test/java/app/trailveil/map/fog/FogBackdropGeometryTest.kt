package app.trailveil.map.fog

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FogBackdropGeometryTest {
    @Test
    fun bandsAndMosaicLeaveNoGapAnywhereInTheSurround() {
        assertSurroundIsSolid(mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16))
    }

    /**
     * The mosaic quad deliberately keeps unwrapped longitudes so a dateline mosaic stays
     * continuous instead of spanning the long way around the world. The bands extend that same
     * unwrapped frame, so they must not wrap either or they would close around the wrong side.
     */
    @Test
    fun bandsStayContinuousWithAMosaicThatStraddlesTheAntimeridian() {
        val mosaic = mosaicAround(GeoPoint(0.0, 179.9999), zoom = 16)
        val bounds = mosaic.bounds
        assertTrue(
            "expected an unwrapped dateline mosaic, was $bounds",
            bounds.eastLongitude > 180.0,
        )
        val bands = FogBackdropGeometry.bands(mosaic)

        assertTrue(
            "the west band wrapped away from the mosaic: ${bands.west}",
            bands.west.eastLongitude > bounds.westLongitude &&
                bands.west.westLongitude < bounds.westLongitude,
        )
        assertTrue(
            "the east band wrapped away from the mosaic: ${bands.east}",
            bands.east.westLongitude < bounds.eastLongitude &&
                bands.east.eastLongitude > bounds.eastLongitude,
        )
        assertSurroundIsSolid(mosaic)
    }

    @Test
    fun bandsLeaveNoGapWhenTheMosaicSpansTheWholeWorldWidth() {
        val mosaic = mosaicAround(GeoPoint(0.0, 0.0), zoom = 1)
        assertEquals(360.0, mosaic.bounds.eastLongitude - mosaic.bounds.westLongitude, 1e-9)

        assertSurroundIsSolid(mosaic)
    }

    @Test
    fun bandsLeaveNoGapWhenTheMosaicIsClippedAtThePole() {
        val mosaic = mosaicAround(GeoPoint(85.0, 10.0), zoom = 4)
        assertEquals(WebMercator.MAX_LATITUDE, mosaic.bounds.northLatitude, 1e-6)

        assertSurroundIsSolid(mosaic)
    }

    @Test
    fun bandsOverlapTheMosaicByHalfOfOneOfItsPixels() {
        val mosaic = mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16)
        val bounds = mosaic.bounds
        val bands = FogBackdropGeometry.bands(mosaic)

        val longitudePixel = (bounds.eastLongitude - bounds.westLongitude) / mosaic.mask.width
        assertEquals(
            longitudePixel * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS,
            bands.west.eastLongitude - bounds.westLongitude,
            longitudePixel * 1e-6,
        )
        assertEquals(
            longitudePixel * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS,
            bounds.eastLongitude - bands.east.westLongitude,
            longitudePixel * 1e-6,
        )
        val northY = WebMercator.normalizedY(bounds.northLatitude)
        val southY = WebMercator.normalizedY(bounds.southLatitude)
        val pixelY = (southY - northY) / mosaic.mask.height
        assertEquals(
            pixelY * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS,
            WebMercator.normalizedY(bands.north.southLatitude) - northY,
            pixelY * 1e-6,
        )
        assertEquals(
            pixelY * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS,
            southY - WebMercator.normalizedY(bands.south.northLatitude),
            pixelY * 1e-6,
        )
    }

    /** Fog is translucent, so two bands over the same map would render as a darker seam. */
    @Test
    fun bandsNeverOverlapEachOther() {
        everyMosaic().forEach { mosaic ->
            val bands = FogBackdropGeometry.bands(mosaic).asList()
            bands.indices.forEach { first ->
                (first + 1 until bands.size).forEach { second ->
                    assertTrue(
                        "bands ${bands[first]} and ${bands[second]} overlap",
                        !overlaps(bands[first], bands[second]),
                    )
                }
            }
        }
    }

    @Test
    fun bandsAreNeverInvertedOrProjectedPastTheWorldEdge() {
        everyMosaic().forEach { mosaic ->
            FogBackdropGeometry.bands(mosaic).asList().forEach { band ->
                assertTrue(
                    "band $band is inverted",
                    band.eastLongitude >= band.westLongitude &&
                        band.northLatitude >= band.southLatitude,
                )
                assertTrue(
                    "band $band reaches past the projected world edge",
                    band.northLatitude <= WebMercator.MAX_LATITUDE + 1e-9 &&
                        band.southLatitude >= -WebMercator.MAX_LATITUDE - 1e-9,
                )
            }
        }
    }

    /**
     * The bound the whole design turns on, stated as a test rather than as a comment.
     *
     * A quad larger than the renderer's precision is not clipped, it is drawn over the entire map
     * and buries the explored area with it — measured, not assumed: a whole-world surround at
     * render zoom 16 is 33.5 million pixels across and hides the revealed track completely. So no
     * quad may exceed the budget, at any zoom, ever.
     */
    @Test
    fun noQuadEverExceedsWhatTheRendererWillDraw() {
        everyZoom().forEach { mosaic ->
            val worldPixels = FogBackdropGeometry.worldPixels(mosaic)
            val quads = FogBackdropGeometry.bands(mosaic).asList() +
                FogBackdropGeometry.worldRepeats(mosaic)
                    .takeIf { FogBackdropGeometry.surroundSpansWorld(mosaic) }
                    .orEmpty() +
                listOfNotNull(FogBackdropGeometry.wrappedSideBand(mosaic)) +
                mosaic.bounds
            quads.forEach { quad ->
                val widthPixels =
                    (quad.eastLongitude - quad.westLongitude) /
                        FogBackdropGeometry.WORLD_LONGITUDE_SPAN * worldPixels
                val heightPixels = (
                    WebMercator.normalizedY(quad.southLatitude) -
                        WebMercator.normalizedY(quad.northLatitude)
                    ) * worldPixels
                assertTrue(
                    "quad is ${widthPixels}x${heightPixels} render pixels, past what this " +
                        "renderer draws: $quad",
                    maxOf(widthPixels, heightPixels) <=
                        FogBackdropGeometry.MAX_SURROUND_WORLD_PIXELS + 1.0,
                )
            }
        }
    }

    /**
     * The surround is a fixed number of screen pixels across at every zoom, up to the point where
     * the world itself is smaller than that and it simply becomes the world. That constancy is the
     * property the two constants it replaced lacked, and the reason a pinch out from zoom 4 could
     * leak 46% while the same pinch at zoom 16 looked fine.
     */
    @Test
    fun theSurroundIsTheSameSizeInScreensAtEveryZoom() {
        everyZoom().forEach { mosaic ->
            val worldPixels = FogBackdropGeometry.worldPixels(mosaic)
            val surround = FogBackdropGeometry.surround(mosaic)
            val widthPixels = (surround.eastLongitude - surround.westLongitude) /
                FogBackdropGeometry.WORLD_LONGITUDE_SPAN * worldPixels
            val expected = minOf(worldPixels, FogBackdropGeometry.MAX_SURROUND_WORLD_PIXELS)
            assertEquals(
                "the surround is $widthPixels pixels where the world is $worldPixels",
                expected,
                widthPixels,
                expected * 1e-9,
            )
            // Centred on the mosaic, not on the antimeridian: a mosaic anywhere is then equally
            // far from both ends of its own coverage.
            assertEquals(
                "the surround is not centred on the mosaic",
                (mosaic.bounds.westLongitude + mosaic.bounds.eastLongitude) / 2.0,
                (surround.westLongitude + surround.eastLongitude) / 2.0,
                1e-9,
            )
        }
    }

    /**
     * How much zoom-out the surround absorbs *geometrically* — that is, before the camera could see
     * past its edges.
     *
     * This used to be the slack limit rather than the binding one, because a second guard covered
     * the map at 0.75 levels out to hide the drift of an oversized quad. `P4-022` retired that
     * guard after `P4-024` shrank the surround and device measurements stopped finding drift. The
     * task requires a real four-level pinch, so this geometric lower bound matches that acceptance
     * criterion instead of the earlier 2.86-level sample.
     */
    @Test
    fun theSurroundAbsorbsTheRequiredFourLevelGesture() {
        everyZoom().forEach { mosaic ->
            if (FogBackdropGeometry.surroundSpansWorld(mosaic)) {
                // Nothing to absorb: this one is the whole world in both directions, and there is
                // no map past it. Asserted rather than skipped, because the skip is only sound if
                // that is true — and it was not, before the verifier found it.
                val extent = FogBackdropGeometry.extent(mosaic)
                assertTrue("world-spanning coverage must wrap", extent.wrapsWorld)
                assertEquals(0.0, extent.northNormalizedY, 1e-9)
                assertEquals(1.0, extent.southNormalizedY, 1e-9)
                return@forEach
            }
            val worldPixels = FogBackdropGeometry.worldPixels(mosaic)
            val surroundPixels = FogBackdropGeometry.surroundHalfWorlds(mosaic) * 2.0 * worldPixels
            val levels = ln(surroundPixels / WIDEST_PHONE_VIEWPORT_PIXELS) / ln(2.0)
            assertTrue(
                "the surround absorbs only $levels zoom levels",
                levels >= MINIMUM_ZOOM_LEVELS_ABSORBED,
            )
        }
    }

    @Test
    fun theSurroundIsTheWholeWorldThroughRenderZoomSixOnly() {
        assertTrue(
            FogBackdropGeometry.surroundSpansWorld(
                mosaicAround(center = GeoPoint(25.0330, 121.5654), zoom = 6),
            ),
        )
        assertFalse(
            FogBackdropGeometry.surroundSpansWorld(
                mosaicAround(center = GeoPoint(25.0330, 121.5654), zoom = 7),
            ),
        )
    }

    /**
     * The viewport can be wider than the world — a landscape window at the zoom MapLibre floors
     * at — and the basemap repeats into that space while an image source does not. Where that can
     * happen, fog is carried three worlds wide, by whichever of the two repeat paths applies.
     */
    @Test
    fun fogCoversThreeWorldsWhereMoreThanOneCanBeSeen() {
        everyZoom().filter { FogBackdropGeometry.surroundSpansWorld(it) }.forEach { mosaic ->
            val surround = FogBackdropGeometry.surround(mosaic)
            val (west, east) = if (FogBackdropGeometry.spansWorld(mosaic)) {
                // The mosaic itself is repeated a world either side, mask and all.
                mosaic.bounds.westLongitude - FogBackdropGeometry.WORLD_LONGITUDE_SPAN to
                    mosaic.bounds.eastLongitude + FogBackdropGeometry.WORLD_LONGITUDE_SPAN
            } else {
                val repeats = FogBackdropGeometry.worldRepeats(mosaic)
                repeats.first().westLongitude to repeats.last().eastLongitude
            }
            assertTrue(
                "fog covers only ${east - west} degrees around $mosaic",
                east - west >= 3.0 * FogBackdropGeometry.WORLD_LONGITUDE_SPAN -
                    2.0 * FogBackdropGeometry.WORLD_REPEAT_OVERLAP_DEGREES - 1e-9,
            )
            assertTrue(
                "the covered span is not centred on the surround",
                kotlin.math.abs(
                    (west + east) / 2.0 -
                        (surround.westLongitude + surround.eastLongitude) / 2.0,
                ) <= 1e-9,
            )
        }
    }

    /** A gap between a world copy and the surround would be bare repeated basemap. */
    @Test
    fun worldRepeatsOverlapTheSurroundRatherThanMeetingIt() {
        everyZoom().filter { FogBackdropGeometry.surroundSpansWorld(it) }.forEach { mosaic ->
            val surround = FogBackdropGeometry.surround(mosaic)
            val (west, east) = FogBackdropGeometry.worldRepeats(mosaic)
            assertEquals(
                FogBackdropGeometry.WORLD_REPEAT_OVERLAP_DEGREES,
                west.eastLongitude - surround.westLongitude,
                1e-9,
            )
            assertEquals(
                FogBackdropGeometry.WORLD_REPEAT_OVERLAP_DEGREES,
                surround.eastLongitude - east.westLongitude,
                1e-9,
            )
            listOf(west, east).forEach { repeat ->
                assertEquals(
                    "a world copy does not span the world's own height",
                    surround.northLatitude,
                    repeat.northLatitude,
                    1e-9,
                )
                assertEquals(
                    surround.southLatitude,
                    repeat.southLatitude,
                    1e-9,
                )
            }
        }
    }

    /**
     * The live check the surface runs on every camera move. It is the reason the reach above is a
     * margin rather than a promise: if a gesture ever did leave the surround, the map is covered
     * instead of leaking.
     */
    @Test
    fun anExtentKnowsWhenACameraHasLeftIt() {
        val mosaic = mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16)
        val extent = FogBackdropGeometry.extent(mosaic)
        val half = extent.halfWorlds

        assertTrue(
            "a camera at the middle of its own surround was reported outside it",
            extent.coversViewport(
                cameraLongitude = 121.5654,
                cameraLatitude = 25.0330,
                viewportHalfWorldsX = half / 2.0,
                viewportHalfWorldsY = half / 4.0,
            ),
        )
        assertFalse(
            "a viewport wider than the surround was reported as covered",
            extent.coversViewport(
                cameraLongitude = 121.5654,
                cameraLatitude = 25.0330,
                viewportHalfWorldsX = half * 1.01,
                viewportHalfWorldsY = 0.0,
            ),
        )
        assertFalse(
            "a camera panned out of the surround was reported as covered",
            extent.coversViewport(
                cameraLongitude = 121.5654 + half * 1.1 * 360.0,
                cameraLatitude = 25.0330,
                viewportHalfWorldsX = 0.0,
                viewportHalfWorldsY = 0.0,
            ),
        )
        assertFalse(
            "a camera moved off the surround in latitude alone was reported as covered",
            extent.coversViewport(
                cameraLongitude = 121.5654,
                cameraLatitude = 70.0,
                viewportHalfWorldsX = 0.0,
                viewportHalfWorldsY = 0.0,
            ),
        )
        // A camera with no position cannot reach `covers` at all: `GeoPoint` refuses a non-finite
        // value by throwing, so the decision belongs to whoever reads the projection, and the only
        // safe one there is "not covered". Pinned here so that guarantee is not quietly relaxed.
        assertThrows(IllegalArgumentException::class.java) {
            GeoPoint(25.0, Double.NaN)
        }
        assertFalse("no corners at all is not covered", extent.covers(emptyList()))
    }

    /**
     * The defect an adversarial verifier found and measured, pinned so it cannot come back.
     *
     * A surround whose budget allows a whole world must cover a whole world *wherever the mosaic
     * sits*. The first version centred a square on the mosaic and let the world edge trim it, so a
     * mosaic north of the equator lost its southern quarter — and the extent then reported that
     * quarter as covered, because it inferred the edges from a radius instead of carrying them.
     * Measured at render zoom 2 before the fix: one pan north presented 27.96% of the screen as
     * bare basemap with the safety cover never raised.
     */
    @Test
    fun aWorldWideSurroundCoversTheWholeWorldWhereverTheMosaicSits() {
        listOf(0.0, 40.0, -40.0, 70.0, -70.0, 84.0, -84.0).forEach { latitude ->
            val mosaic = mosaicAround(GeoPoint(latitude, 121.5654), zoom = 2)
            val surround = FogBackdropGeometry.surround(mosaic)
            val extent = FogBackdropGeometry.extent(mosaic)
            assertEquals(0.5, extent.halfWorlds, 1e-9)

            assertEquals(
                "at latitude $latitude the surround stops short of the north edge",
                0.0,
                WebMercator.normalizedY(surround.northLatitude),
                1e-9,
            )
            assertEquals(
                "at latitude $latitude the surround stops short of the south edge",
                1.0,
                WebMercator.normalizedY(surround.southLatitude),
                1e-9,
            )
            assertEquals(0.0, extent.northNormalizedY, 1e-9)
            assertEquals(1.0, extent.southNormalizedY, 1e-9)
            assertTrue(
                "a world-wide surround at latitude $latitude claimed not to cover a pole",
                extent.coversViewport(
                    cameraLongitude = 121.5654,
                    cameraLatitude = 84.0,
                    viewportHalfWorldsX = 0.2,
                    viewportHalfWorldsY = 0.45,
                ),
            )
        }
    }

    /**
     * The other half of that defect: where the surround genuinely does stop short, the extent has
     * to say so. Inferring the edges from a radius reported this case as covered too.
     */
    @Test
    fun aSurroundShortOfThePolesReportsACameraBeyondItAsUncovered() {
        // Render zoom 16, where the pixel budget binds and the surround is a small window.
        val mosaic = mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16)
        val extent = FogBackdropGeometry.extent(mosaic)
        assertTrue("expected a clamped surround", extent.halfWorlds < 0.5)
        assertFalse("expected a surround with real edges", extent.wrapsWorld)

        val northEdge = WebMercator.latitudeAtNormalizedY(extent.northNormalizedY)
        assertTrue(
            "a camera inside the surround was reported as uncovered",
            extent.coversViewport(121.5654, 25.0330, 0.0, 0.0),
        )
        assertFalse(
            "a camera past the surround's northern edge was reported as covered",
            extent.coversViewport(121.5654, northEdge + 1.0, 0.0, 0.0),
        )
        assertFalse(
            "a viewport reaching past the surround's northern edge was reported as covered",
            extent.coversViewport(121.5654, 25.0330, 0.0, extent.halfWorlds * 1.5),
        )
    }

    /**
     * The second defect from the same verification: coverage that wraps the world has no east or
     * west edge, because a world copy is installed on each side of it. Measuring the camera against
     * one world instead of three raised the black safety cover on an ordinary 60-pixel pan at zoom
     * 1 for anyone more than about a hundred degrees from the prime meridian.
     */
    @Test
    fun aWrappingSurroundIsNeverLeftSideways() {
        val mosaic = mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 1)
        val extent = FogBackdropGeometry.extent(mosaic)
        assertTrue("expected coverage that wraps the world", extent.wrapsWorld)

        // Taipei at zoom 1 on a 1080-pixel display: a third of a world from the mosaic's centre,
        // with a fifth of a world of viewport either side. This is what used to blank the map.
        assertTrue(
            extent.coversViewport(
                cameraLongitude = 121.5654,
                cameraLatitude = 25.0330,
                viewportHalfWorldsX = 0.2009,
                viewportHalfWorldsY = 0.4464,
            ),
        )
        assertTrue(
            "a camera on the far side of the world was reported as uncovered",
            extent.coversViewport(-121.5654, 0.0, 0.2009, 0.4464),
        )
    }

    /**
     * The two ways of covering the neighbouring worlds are exclusive, and which one applies is
     * decided by whether the mosaic spans a world. That is only safe because a mosaic wide enough
     * to span one is also tall enough to fill one: otherwise its repeats would carry fog across
     * some latitudes and nothing across the rest.
     */
    @Test
    fun aMosaicThatSpansTheWorldAlsoFillsIt() {
        (0..22).forEach { zoom ->
            listOf(GeoPoint(0.0, 0.0), GeoPoint(84.0, 179.0), GeoPoint(-84.0, -179.0))
                .forEach { center ->
                    val mosaic = mosaicAround(center, zoom)
                    if (!FogBackdropGeometry.spansWorld(mosaic)) return@forEach
                    assertEquals(
                        "a world-spanning mosaic at zoom $zoom stops short of the north edge",
                        0.0,
                        WebMercator.normalizedY(mosaic.bounds.northLatitude),
                        1e-9,
                    )
                    assertEquals(
                        "a world-spanning mosaic at zoom $zoom stops short of the south edge",
                        1.0,
                        WebMercator.normalizedY(mosaic.bounds.southLatitude),
                        1e-9,
                    )
                }
        }
    }

    /**
     * The regression this geometry exists for, at the zoom that produced it: a pinch out from
     * zoom 4, which is where the old fixed 0.125-of-the-world reach was barely one screen tall.
     */
    @Test
    fun theSurroundAtZoomFourIsTheWholeWorldRatherThanOneScreen() {
        val mosaic = mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 4)
        val worldPixels = FogBackdropGeometry.worldPixels(mosaic)
        assertTrue(
            "the world at render zoom 4 is $worldPixels pixels, which should be well inside " +
                "the budget",
            worldPixels < FogBackdropGeometry.MAX_SURROUND_WORLD_PIXELS,
        )
        assertTrue(
            "the surround at render zoom 4 is not the whole world, which is what the leak was",
            FogBackdropGeometry.surroundSpansWorld(mosaic),
        )
        // The reach that leaked: 0.125 of the world's height per side, against a viewport that a
        // single pinch grew to most of the world.
        val oldReachPixels = 0.125 * 2.0 * worldPixels
        assertTrue(
            "the old reach of $oldReachPixels pixels already covered the screen, so this is not " +
                "the geometry that leaked",
            oldReachPixels < worldPixels,
        )
    }

    /**
     * Every point of the mosaic's own surround has to be painted by something. Sampling is
     * densest immediately around the mosaic, which is the only place a rounding gap could hide.
     */
    private fun assertSurroundIsSolid(mosaic: FogTileMosaic) {
        val bands = FogBackdropGeometry.bands(mosaic)
        val shapes = bands.asList() + mosaic.bounds
        val surround = FogBackdropGeometry.surround(mosaic)
        samplePoints(surround, mosaic.bounds)
            .filter { point -> contains(surround, point) }
            .forEach { point ->
                assertTrue(
                    "$point lies in the surround but in neither the mosaic nor any band",
                    shapes.any { contains(it, point) },
                )
            }
    }

    private fun samplePoints(
        surround: FogTileBounds,
        mosaic: FogTileBounds,
    ): List<GeoPoint> = buildList {
        val latitudeSpan = surround.northLatitude - surround.southLatitude
        val longitudeSpan = surround.eastLongitude - surround.westLongitude
        (0..SURROUND_SAMPLES).forEach { row ->
            (0..SURROUND_SAMPLES).forEach { column ->
                add(
                    GeoPoint(
                        latitude = surround.southLatitude +
                            latitudeSpan * row / SURROUND_SAMPLES,
                        longitude = surround.westLongitude +
                            longitudeSpan * column / SURROUND_SAMPLES,
                    ),
                )
            }
        }
        val mosaicLatitudeSpan = mosaic.northLatitude - mosaic.southLatitude
        val mosaicLongitudeSpan = mosaic.eastLongitude - mosaic.westLongitude
        (-6..6).forEach { row ->
            (-6..6).forEach { column ->
                add(
                    GeoPoint(
                        latitude = (
                            mosaic.southLatitude + mosaicLatitudeSpan * (0.5 + row * 0.0834)
                            ).coerceIn(-WebMercator.MAX_LATITUDE, WebMercator.MAX_LATITUDE),
                        longitude = mosaic.westLongitude +
                            mosaicLongitudeSpan * (0.5 + column * 0.0834),
                    ),
                )
            }
        }
    }

    private fun contains(bounds: FogTileBounds, point: GeoPoint): Boolean =
        point.latitude >= bounds.southLatitude - EDGE_TOLERANCE &&
            point.latitude <= bounds.northLatitude + EDGE_TOLERANCE &&
            point.longitude >= bounds.westLongitude - EDGE_TOLERANCE &&
            point.longitude <= bounds.eastLongitude + EDGE_TOLERANCE

    private fun overlaps(first: FogTileBounds, second: FogTileBounds): Boolean {
        val latitudeOverlap = min(first.northLatitude, second.northLatitude) -
            max(first.southLatitude, second.southLatitude)
        val longitudeOverlap = min(first.eastLongitude, second.eastLongitude) -
            max(first.westLongitude, second.westLongitude)
        return latitudeOverlap > EDGE_TOLERANCE && longitudeOverlap > EDGE_TOLERANCE
    }

    /** One mosaic per zoom level, which is the axis every size rule here depends on. */
    private fun everyZoom(): List<FogTileMosaic> =
        (0..22).map { zoom -> mosaicAround(GeoPoint(25.0330, 121.5654), zoom) } +
            (0..22).map { zoom -> mosaicAround(GeoPoint(84.0, 179.5), zoom) }

    private fun everyMosaic(): List<FogTileMosaic> = listOf(
        mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16),
        mosaicAround(GeoPoint(0.0, 179.9999), zoom = 16),
        mosaicAround(GeoPoint(-85.0, -170.0), zoom = 4),
        mosaicAround(GeoPoint(85.0, 10.0), zoom = 4),
        mosaicAround(GeoPoint(0.0, 0.0), zoom = 2),
        mosaicAround(GeoPoint(0.0, 0.0), zoom = 1),
        mosaicAround(GeoPoint(0.0, 0.0), zoom = 0),
        mosaicAround(GeoPoint(48.8566, 2.3522), zoom = 22),
    )

    /**
     * Nothing the renderer is repeating for us may sit wholly outside the world it repeats.
     *
     * Below the zoom where MapLibre repeats an image source across world copies by itself, a quad
     * lying entirely past the world's edge is drawn twice — at its own coordinates and again where
     * the repetition puts it, on the same pixels. Measured on the production style with every other
     * fog layer hidden: 5.31% of the screen under a second coat at an ordinary place, 8.25% past
     * the antimeridian, and nothing at all from the quads that merely cross the edge.
     *
     * The two camera-anchored side bands are not in this set, because they are not drawn in this
     * regime; the single wrapped band that replaces them is.
     */
    @Test
    fun noQuadDrawnWhereTheRendererRepeatsLiesWhollyOutsideTheWorld() {
        var checked = 0
        (-180..180 step 5).forEach { longitude ->
            listOf(0, 1, 2, 3, 6, 10, 12).forEach { zoom ->
                val camera = GeoPoint(25.0330, longitude.toDouble())
                val mosaic = mosaicAround(camera, zoom).anchoredNear(camera.longitude)
                val bands = FogBackdropGeometry.bands(mosaic)
                val drawn = listOfNotNull(
                    "mosaic" to mosaic.bounds,
                    "north" to bands.north,
                    "south" to bands.south,
                    FogBackdropGeometry.wrappedSideBand(mosaic)?.let { "wrapped side" to it },
                )
                drawn.forEach { (name, quad) ->
                    // A quad exactly one world wide lands on itself under repetition wherever it
                    // starts, so where it starts cannot matter. The world-wide mosaic at the
                    // antimeridian is measured clean at camera zoom 0.98.
                    if (
                        quad.eastLongitude - quad.westLongitude >=
                        FogBackdropGeometry.WORLD_LONGITUDE_SPAN - EDGE_TOLERANCE
                    ) {
                        return@forEach
                    }
                    checked += 1
                    assertTrue(
                        "at longitude $longitude zoom $zoom the $name quad lies wholly outside " +
                            "the world and would be drawn twice: $quad",
                        quad.westLongitude < 180.0 && quad.eastLongitude > -180.0,
                    )
                }
            }
        }
        assertTrue("nothing was checked", checked > 0)
    }

    /**
     * The wrapped band is the world minus the mosaic, so the two together are one world and one
     * world only — anything less is bare map beside the mosaic, anything more is a second coat.
     */
    @Test
    fun theWrappedSideBandAndTheMosaicTileTheWorldExactlyOnce() {
        listOf(121.5654, 179.5, -179.5, 0.0, -121.0, 45.0).forEach { longitude ->
            val camera = GeoPoint(25.0330, longitude)
            val mosaic = mosaicAround(camera, zoom = 3).anchoredNear(longitude)
            val band = requireNotNull(FogBackdropGeometry.wrappedSideBand(mosaic)) {
                "expected a wrapped side band at longitude $longitude"
            }
            val bounds = mosaic.bounds
            val overlap = (bounds.eastLongitude - bounds.westLongitude) /
                mosaic.mask.width * FogBackdropGeometry.MOSAIC_OVERLAP_PIXELS
            assertEquals(
                "at longitude $longitude the band and the mosaic do not make one world",
                360.0 + overlap * 2.0,
                (band.eastLongitude - band.westLongitude) +
                    (bounds.eastLongitude - bounds.westLongitude),
                1e-6,
            )
            // The band's own edges, whichever copy of the world it was placed in, are the mosaic's
            // edges pulled half a mosaic pixel inwards.
            assertEquals(
                "at longitude $longitude the band does not start at the mosaic's east edge",
                0.0,
                worldsBetween(band.westLongitude, bounds.eastLongitude - overlap),
                1e-9,
            )
            assertEquals(
                "at longitude $longitude the band does not end at the mosaic's west edge",
                0.0,
                worldsBetween(band.eastLongitude, bounds.westLongitude + overlap),
                1e-9,
            )
        }
    }

    /** A mosaic that is already a whole world has nothing beside it to cover. */
    @Test
    fun aWorldWideMosaicIsGivenNoWrappedSideBand() {
        assertEquals(null, FogBackdropGeometry.wrappedSideBand(mosaicAround(GeoPoint(0.0, 0.0), 1)))
        assertEquals(null, FogBackdropGeometry.wrappedSideBand(mosaicAround(GeoPoint(0.0, 0.0), 0)))
    }

    /**
     * Where the surround is clamped rather than world-wide there is no repetition to be caught by,
     * and the two side bands sit right beside the mosaic where they belong.
     */
    @Test
    fun aClampedSurroundIsGivenNoWrappedSideBand() {
        assertEquals(
            null,
            FogBackdropGeometry.wrappedSideBand(mosaicAround(GeoPoint(25.0330, 121.5654), 16)),
        )
    }

    @Test
    fun aQuadPastTheWorldEdgeIsMovedBackByWholeWorlds() {
        val past = FogTileBounds(
            westLongitude = 180.0,
            southLatitude = -10.0,
            eastLongitude = 405.0,
            northLatitude = 10.0,
        )
        val anchored = FogBackdropGeometry.anchoredInsideWorld(past)

        assertEquals(-180.0, anchored.westLongitude, 1e-9)
        assertEquals(45.0, anchored.eastLongitude, 1e-9)
        assertEquals(past.southLatitude, anchored.southLatitude, 1e-9)
        assertEquals(past.northLatitude, anchored.northLatitude, 1e-9)
        // A quad that already straddles the edge is drawn once, so it is left exactly as it is.
        val straddling = past.copy(westLongitude = 90.0, eastLongitude = 225.0)
        assertEquals(straddling, FogBackdropGeometry.anchoredInsideWorld(straddling))
    }

    /**
     * A trapezoid whose far edge leaves the surround while its axis-aligned box does not.
     *
     * This is the shape the change exists for and, until this case, the only shape the JVM suite
     * never asked about: every other case here rebuilds a rectangle through `coversViewport`. A
     * tilted camera's visible ground is wider far away than near, so the near corners can sit well
     * inside a surround that the far ones have left — which is exactly what `covers` used to be
     * unable to see, at a measured cost of 14.75% of the screen.
     */
    @Test
    fun aViewThatLeavesTheSurroundOnlyAtItsFarEdgeIsNotCovered() {
        val mosaic = mosaicAround(GeoPoint(25.0330, 121.5654), zoom = 16)
        val extent = FogBackdropGeometry.extent(mosaic)
        val half = extent.halfWorlds * FogBackdropGeometry.WORLD_LONGITUDE_SPAN

        val near = GeoPoint(25.0330, 121.5654)
        assertTrue(
            "the near edge alone should be well inside",
            extent.covers(
                listOf(
                    GeoPoint(near.latitude, near.longitude - half * 0.1),
                    GeoPoint(near.latitude, near.longitude + half * 0.1),
                ),
            ),
        )
        // The same view, tilted: two near corners inside, two far corners past the edge. The
        // axis-aligned box of the near pair would still say covered.
        val farLatitude = WebMercator.latitudeAtNormalizedY(
            WebMercator.normalizedY(near.latitude) - extent.halfWorlds * 1.2,
        )
        assertFalse(
            "a view whose far edge has left the surround was reported as covered",
            extent.covers(
                listOf(
                    GeoPoint(farLatitude, near.longitude - half * 1.4),
                    GeoPoint(farLatitude, near.longitude + half * 1.4),
                    GeoPoint(near.latitude, near.longitude + half * 0.1),
                    GeoPoint(near.latitude, near.longitude - half * 0.1),
                ),
            ),
        )
    }

    /**
     * A view straddling the antimeridian is one interval, not two far-apart ones.
     *
     * `covers` folds each corner about the surround's own centre for this reason. The behaviour was
     * measured by a verifier and correct; nothing in the tree asked for it until now.
     */
    @Test
    fun aViewStraddlingTheAntimeridianIsMeasuredAsOneInterval() {
        val mosaic = mosaicAround(GeoPoint(0.0, 179.97), zoom = 16)
        val extent = FogBackdropGeometry.extent(mosaic)
        val center = extent.centerLongitude
        val reach = extent.halfWorlds * FogBackdropGeometry.WORLD_LONGITUDE_SPAN

        fun wrapped(longitude: Double): Double {
            var value = longitude
            while (value > 180.0) value -= 360.0
            while (value < -180.0) value += 360.0
            return value
        }
        assertTrue(
            "a view just inside the surround but across the seam was reported as outside",
            extent.covers(
                listOf(
                    GeoPoint(0.0, wrapped(center - reach * 0.5)),
                    GeoPoint(0.0, wrapped(center + reach * 0.5)),
                ),
            ),
        )
        assertFalse(
            "a view past the surround's edge across the seam was reported as covered",
            extent.covers(
                listOf(
                    GeoPoint(0.0, wrapped(center - reach * 0.5)),
                    GeoPoint(0.0, wrapped(center + reach * 1.5)),
                ),
            ),
        )
    }

    /** How many whole worlds apart two longitudes are, which must be an integer or they differ. */
    private fun worldsBetween(first: Double, second: Double): Double {
        val worlds = (first - second) / 360.0
        return worlds - Math.round(worlds)
    }

    /**
     * The old axis-aligned question, asked through the new corner-shaped one.
     *
     * `covers` takes the corners of what the camera can really see, because a tilted or turned
     * camera sees a trapezoid rather than a box — see its own docstring for what that cost. These
     * cases are all about the surround's edges rather than about the camera's shape, so they build
     * the four corners of an untilted, north-up viewport and ask that.
     */
    private fun FogSurroundExtent.coversViewport(
        cameraLongitude: Double,
        cameraLatitude: Double,
        viewportHalfWorldsX: Double,
        viewportHalfWorldsY: Double,
    ): Boolean {
        val centerY = WebMercator.normalizedY(cameraLatitude)
        val north = WebMercator.latitudeAtNormalizedY(
            (centerY - viewportHalfWorldsY).coerceIn(0.0, 1.0),
        )
        val south = WebMercator.latitudeAtNormalizedY(
            (centerY + viewportHalfWorldsY).coerceIn(0.0, 1.0),
        )
        val west = cameraLongitude - viewportHalfWorldsX * FogBackdropGeometry.WORLD_LONGITUDE_SPAN
        val east = cameraLongitude + viewportHalfWorldsX * FogBackdropGeometry.WORLD_LONGITUDE_SPAN
        return covers(
            listOf(
                GeoPoint(north, west),
                GeoPoint(north, east),
                GeoPoint(south, east),
                GeoPoint(south, west),
            ),
        )
    }

    private fun mosaicAround(center: GeoPoint, zoom: Int): FogTileMosaic {
        val renderer = FogTileRenderer()
        val keys = FogViewportTileGrid.around(center = center, zoom = zoom, renderVersion = 0)
        return FogPocMosaic.compose(
            keys.map { key -> FogMosaicTile(key, renderer.render(key, emptyList())) },
        )
    }

    private companion object {
        const val EDGE_TOLERANCE = 1e-9
        const val SURROUND_SAMPLES = 60

        /** A tablet in landscape, so the margin below is the one on the widest plausible screen. */
        const val WIDEST_PHONE_VIEWPORT_PIXELS = 2_400.0

        /**
         * This used to be nine, which the surround met by being about five thousand screens wide —
         * and being that wide is what made the renderer draw its inner edge fifty screen pixels
         * away from the mosaic, as a black band a user photographed twice. Headroom past what one
         * gesture can use is not free, so this pins the four-level acceptance requirement and no
         * more.
         */
        const val MINIMUM_ZOOM_LEVELS_ABSORBED = 4.0
    }
}
