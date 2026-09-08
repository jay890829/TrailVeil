package app.trailveil.map

import android.graphics.Bitmap
import app.trailveil.map.fog.FogBackdropGeometry
import app.trailveil.map.fog.FogMaskArgb
import app.trailveil.map.fog.FogMosaicTile
import app.trailveil.map.fog.FogPocMosaic
import app.trailveil.map.fog.FogSurroundExtent
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileMosaic
import app.trailveil.map.fog.FogTilePngCodec
import app.trailveil.map.fog.FogViewportCoverageRequest
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.WebMercator
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.GroundOverlay
import com.google.android.gms.maps.model.GroundOverlayOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions

/**
 * `V03-011` arm 2 (prototype A): one generation, one anchored image, no tiles.
 *
 * The render, the masks, the generation lifecycle and the snapshot proof are the shipped ones. This
 * class only changes how a published generation reaches the screen: the floor rectangle's masks are
 * composed into one mosaic, rasterised once, and anchored to the mosaic's ground bounds as a
 * `GroundOverlay`. An in-extent pinch, pan or tilt then stretches that image instead of asking for
 * tiles, which is the whole point - a coverage key carries the zoom it was planned at, so no tile
 * ring survives an integer zoom step, and arm 1 measured exactly that ceiling.
 *
 * **`googlePoc` only.** It is selected by a per-build-type seam whose `googleRelease` twin returns
 * null unconditionally and cannot name this class, so it is physically absent from a published APK.
 *
 * ### Four deliberate differences from the tile path, each fail-closed
 *
 * 1. **The predecessor IS hidden at the reveal - owner decision 2026-09-08, reversing what this
 *    class originally did.** The old choice was to leave it up: there is no delivery barrier here,
 *    so nothing proves the successor painted before it is shown, and a brief double coat of fog
 *    was the fail-closed trade. The reasoning was right; the cost estimate was wrong. It budgeted
 *    "a few hundred milliseconds" and measured on the device it lasted until the NEXT camera
 *    movement - screen mean 50.8 against the tile path's 118.8, with this arm's own holes showing
 *    the predecessor's fog through them. An arm whose boundary cannot be seen cannot be judged,
 *    which is what these builds exist for. See [reveal] for what bounds the exposure now. The
 *    vector installer deliberately does NOT follow: it never had the defect, and hiding there
 *    loses the map.
 * 2. **A mosaic that crosses the antimeridian is refused.** `FogPocMosaic` deliberately leaves its
 *    longitude bounds unwrapped so a dateline mosaic is continuous; `LatLng` normalises longitude
 *    into [-180, 180), which would silently anchor such an image the long way round the world.
 *    Refusing fails the generation, which raises the cover - correct, and loud.
 * 3. **The raster is capped, not tile-resolution.** See [targetSizeFor].
 * 4. **The surround it reports is the IMAGE, not the tile path's backdrop.** See [imageExtent] -
 *    the one place this class first got wrong, and the reason the spike has a zoom-out control.
 *
 * ### One thing measured rather than assumed
 *
 * Whether `BitmapDescriptorFactory.fromBitmap` copies or retains its argument is not documented and
 * is not derivable from the AAR. This class keeps every bitmap referenced beside its overlay and
 * recycles it only after the overlay is removed, which is correct under either answer. If the SDK
 * retains, the recycle is still ordered after the layer is gone; if it copies, the bitmap was dead
 * from the start and the reference merely delayed collection.
 */
internal class GoogleFogMosaicOverlayInstaller(
    private val map: GoogleMap,
) : GoogleFogOverlayInstaller {

    private class Installed(
        val overlay: GroundOverlay,
        val bitmap: Bitmap,
        /**
         * The ground this generation can actually defend.
         *
         * The image's own rectangle when the backdrop failed to attach, the whole surround when it
         * did. See [imageExtent] for why answering with a reach that is not drawn is the one defect
         * this class shipped, and [attachBackdrop] for what draws the rest of it.
         */
        val extent: FogSurroundExtent,
        val backdrop: List<Polygon>,
        val width: Int,
        val height: Int,
    )

    private val installed = LinkedHashMap<Long, Installed>()
    private var refusals = 0
    private var lastRefusal: String? = null

    override fun demoteExisting(): Boolean = installed.values.all { entry ->
        try {
            entry.overlay.zIndex = OLD_OVERLAY_Z
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    override fun attach(
        generationId: Long,
        coverage: FogViewportCoverageRequest,
        tiles: List<FogMosaicTile>,
    ): Boolean {
        val mosaic = try {
            FogPocMosaic.compose(tiles)
        } catch (failure: IllegalArgumentException) {
            return refuse("compose:" + failure.javaClass.simpleName)
        }
        val bounds = boundsOrNull(mosaic) ?: return refuse("unwrappedBounds")

        val (width, height) = targetSizeFor(mosaic)
        val pixels = FogMaskArgb.toArgbScaledForGeneration(
            mask = mosaic.mask,
            generation = generationId,
            targetWidth = width,
            targetHeight = height,
        )
        val bitmap = try {
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return refuse("bitmap")
        } catch (_: OutOfMemoryError) {
            return refuse("oom")
        }

        val overlay = try {
            map.addGroundOverlay(
                GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                    .positionFromBounds(bounds)
                    // Attached invisible, exactly like the tile path's hidden overlay - except that
                    // this one really is doing nothing until the reveal, because there is nothing
                    // for it to fetch.
                    .transparency(HIDDEN_FOG_TRANSPARENCY)
                    .zIndex(NEW_OVERLAY_Z),
            )
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        if (overlay == null) {
            bitmap.recycle()
            return refuse("addGroundOverlay")
        }

        // The backdrop, and the extent this generation may therefore claim. A failure here is
        // not a failed generation: the image is already attached and correct, and answering from
        // the image's own rectangle is the fail-closed reading of a surface that drew less than the
        // tile path would have. It costs cover, never fog.
        val backdrop = attachBackdrop(mosaic, generationId)
        installed[generationId] = Installed(
            overlay = overlay,
            bitmap = bitmap,
        // **The extent is the IMAGE, never the surround, and this is the correction the owner's
        // "some places never reveal" report came down to.**
        //
        // `covers()` is what the coordinator asks to decide whether the camera has left what this
        // surface can defend; a false answer suppresses the rebuild. Claiming
        // `FogBackdropGeometry.extent` claimed the SURROUND - +/-40 tiles at the render zoom, which
        // at z18 is kilometres - while the only ground carrying holes is the drawn image, a couple
        // of tiles across. Everything between the two is the backdrop, which is uniform fog with no
        // holes at all. So the surface answered "covered" for ground it merely PAINTS, and since a
        // hand gesture yields LEAVE_PUBLISHED_COVERAGE and never sets `viewportDirty`, the idle
        // rebuild was skipped: pan off the first image and every explored metre beyond it stays
        // fogged for good, with the cover down and nothing to make it try again.
        //
        // The invariant this restores: answer from the ground this surface can REVEAL, never from
        // the ground it can paint. Section 15k's defect was the same sentence with the opposite
        // sign - claiming a reach that was not drawn - and it cost 89.344% of bare basemap.
        //
        // The price is real and is the right price: the claim is now small, so leaving the image
        // raises the cover and forces a rebuild. That is exactly the churn the tile arms already
        // pay, and paying it is what makes this arm comparable to them rather than flattered by a
        // blindfold.
            extent = imageExtent(mosaic),
            backdrop = backdrop,
            width = width,
            height = height,
        )
        return true
    }

    /**
     * Fills the ground BETWEEN the anchored image and the surround, which the tile path gets free.
     *
     * `V03-011` section 15k: the shipped fog is not only its tile rectangle. Every key outside the
     * published set is answered by `GoogleGenerationBoundTileProvider` with opaque fog, so a
     * `TileOverlay` covering the world IS its own backdrop and ground beyond the plan stays fogged
     * without anything being drawn for it. One anchored image has no such answer, and the zoom-out
     * control measured what that costs: 89.344% of the map on screen as bare basemap, because the
     * installer reported the tile path's reach while drawing a fraction of it.
     *
     * The complement is `FogBackdropGeometry.extentGuard`, which the OTHER provider already draws
     * as filled polygons - this is the same geometry through Google's `Polygon` instead of a
     * GeoJSON source. Vector rather than raster on purpose: the backdrop is uniform fog, so it has
     * no resolution to lose, and rasterising the surround into the mosaic instead would spend the
     * image's pixel budget on ground that carries no information and would coarsen the reveal
     * boundary that section 15l measures.
     *
     * Attached invisible, like the image, and revealed with it.
     *
     * **Colour matched to the generation, not to the default fog.** A published generation writes
     * `FogTilePngCodec.colorForGeneration` into every fog pixel of its mask, and the snapshot proof
     * recognises the surface by exactly that. A backdrop painted the default colour would put a
     * seam between the image and its own surround at every generation whose signature is not the
     * default, and would present the prover with two different fogs for one generation.
     */
    private fun attachBackdrop(mosaic: FogTileMosaic, generationId: Long): List<Polygon> {
        // The complement of the IMAGE, not of the surround, and the first version of this got it
        // wrong in a way only a device could show. `extentGuard(extent(mosaic))` is what the other
        // provider passes, because there the mosaic is drawn inside a surround that reaches several
        // screens further and the guard only has to fill what lies beyond THAT. Here the image is
        // the whole of the drawn area, so the band between the image and the surround is exactly
        // what a zoom-out uncovers - and the measurement said so: seven polygons attached and the
        // same 89.344% of bare basemap came back, because not one of them was anywhere near the
        // ground being exposed.
        //
        // Two things happen to those rectangles before any of them becomes a Polygon, and the
        // proof failure that found them is recorded in section 15m of the evidence.
        //
        // `anchoredInsideWorld` is the geometry's own remedy for a quad lying wholly past the
        // world's edge, which the renderer then draws twice - once where its coordinates put it and
        // once where its repetition of world copies does - as a second coat of fog. A mosaic near
        // the antimeridian is exactly where `longitudeGuardIntervals` emits such a quad.
        //
        // `splitForGooglePolygon` is the same defect arriving by the other road. The guard is
        // authored for GeoJSON, whose convention makes an exactly-180-degree ring unambiguous, and
        // it deliberately emits them: `splitForCanonicalGeoJson` stops splitting AT 180. A Google
        // `Polygon` has no such convention - it joins consecutive vertices the short way round in
        // longitude, and at exactly 180 there is no short way - so the ring may enclose the
        // complementary half of the world, which contains the image. That is a second coat over
        // every pixel the proof samples, and it is what generations 2, 3 and 4 died of.
        val rectangles = try {
            surroundComplementOfImage(mosaic)
                .map(FogBackdropGeometry::anchoredInsideWorld)
                .flatMap { bounds -> bounds.splitForGooglePolygon() }
        } catch (_: IllegalArgumentException) {
            refuse("guardGeometry")
            return emptyList()
        }
        if (rectangles.isEmpty()) return emptyList()
        val signature = FogTilePngCodec.colorForGeneration(generationId)
        val fill = argb(VISIBLE_FOG_ALPHA, signature.red, signature.green, signature.blue)
        val polygons = mutableListOf<Polygon>()
        rectangles.forEach { bounds ->
            val polygon = try {
                map.addPolygon(
                    PolygonOptions()
                        .addAll(bounds.ring())
                        // Rhumb lines, so a rectangle in longitude and latitude stays one: a
                        // geodesic edge would bow away from the parallel it is supposed to follow
                        // and leave a sliver of unfogged ground against the image.
                        .geodesic(false)
                        .fillColor(fill)
                        .strokeWidth(0f)
                        .strokeColor(TRANSPARENT)
                        .zIndex(BACKDROP_Z)
                        .visible(false)
                        .clickable(false),
                )
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
            if (polygon == null) {
                polygons.forEach { attached -> removeSafely(attached) }
                refuse("addPolygon")
                return emptyList()
            }
            polygons += polygon
        }
        return polygons
    }

    /**
     * The ground this generation claims but does not draw: the surround, minus the image.
     *
     * The first two versions of this complemented to the WHOLE WORLD, which is what
     * `FogBackdropGeometry.extentGuard` builds, because that is what the tile path effectively
     * covers - `GoogleGenerationBoundTileProvider` answers every key outside the published set with
     * opaque fog, everywhere, forever. Copying that reach here was wrong twice over.
     *
     * It is wrong on contract. What this surface CLAIMS is `FogBackdropGeometry.extent(mosaic)`,
     * the surround, and the coordinator raises the cover the moment the camera leaves it. Ground
     * beyond the surround is the cover's job, not this backdrop's, so drawing it buys nothing.
     *
     * It is wrong in practice, and that is what found it. A world complement puts a quad from the
     * surround's edge to `WebMercator.MAX_LATITUDE` across every longitude - in Mercator, the most
     * distorted geometry the projection can express - and with those attached the SDK stopped
     * answering `GoogleMap.snapshot()` at all: `V03-011` section 15o, generation 2's proof shows
     * `attempt:1` with no `snapshot:` event ever following it, in both backdrop builds and in
     * neither build without one. No snapshot is no proof, and no proof is a cover that never comes
     * down.
     *
     * Four strips, each clipped to the surround, and any that comes out degenerate is dropped
     * rather than emitted as a zero-area ring.
     */
    private fun surroundComplementOfImage(mosaic: FogTileMosaic): List<FogTileBounds> {
        val surround = FogBackdropGeometry.extent(mosaic)
        val image = mosaic.bounds
        val north = WebMercator.latitudeAtNormalizedY(surround.northNormalizedY)
        val south = WebMercator.latitudeAtNormalizedY(surround.southNormalizedY)
        val halfDegrees = surround.halfWorlds * FogBackdropGeometry.WORLD_LONGITUDE_SPAN
        // A surround that reaches all the way round has no east or west edge to complement; its
        // north and south strips still span only the latitudes the surround owns.
        val west = if (surround.wrapsWorld) {
            -FogBackdropGeometry.WORLD_LONGITUDE_SPAN / 2.0
        } else {
            surround.centerLongitude - halfDegrees
        }
        val east = if (surround.wrapsWorld) {
            FogBackdropGeometry.WORLD_LONGITUDE_SPAN / 2.0
        } else {
            surround.centerLongitude + halfDegrees
        }
        val strips = mutableListOf<FogTileBounds>()
        fun add(westLongitude: Double, southLatitude: Double, eastLongitude: Double, northLatitude: Double) {
            if (eastLongitude - westLongitude <= 0.0 || northLatitude - southLatitude <= 0.0) return
            if (!westLongitude.isFinite() || !eastLongitude.isFinite()) return
            if (!southLatitude.isFinite() || !northLatitude.isFinite()) return
            strips += FogTileBounds(
                westLongitude = westLongitude,
                southLatitude = southLatitude,
                eastLongitude = eastLongitude,
                northLatitude = northLatitude,
            )
        }
        // North and south own the full width, so the corners belong to them and the side strips
        // only span the image's own latitudes. Same division of labour as extentGuard.
        add(west, image.northLatitude, east, north)
        add(west, south, east, image.southLatitude)
        add(west, image.southLatitude, image.westLongitude, image.northLatitude)
        add(image.eastLongitude, image.southLatitude, east, image.northLatitude)
        return strips
    }

    /**
     * Halves a guard rectangle until no ring spans enough longitude to be read the long way round.
     *
     * The ceiling is well under 180 rather than just under it. What a `Polygon` does at exactly 180
     * is undefined rather than merely tight, and a rectangle whose width sits a rounding error
     * below the boundary would put the whole surface on the far side of an SDK's tie-break. Halving
     * costs one more polygon per split and nothing per frame.
     */
    private fun FogTileBounds.splitForGooglePolygon(): List<FogTileBounds> {
        val width = eastLongitude - westLongitude
        if (!width.isFinite() || width <= MAX_POLYGON_RING_DEGREES) return listOf(this)
        val middle = (westLongitude + eastLongitude) / 2.0
        if (middle <= westLongitude || middle >= eastLongitude) return listOf(this)
        return listOf(
            copy(eastLongitude = middle),
            copy(westLongitude = middle),
        ).flatMap { half -> half.splitForGooglePolygon() }
    }

    private fun removeSafely(polygon: Polygon) {
        try {
            polygon.remove()
        } catch (_: Exception) {
            // The surface is going away with it, or it is already gone.
        } catch (_: LinkageError) {
            // As above.
        }
    }

    private fun FogTileBounds.ring(): List<LatLng> = listOf(
        LatLng(southLatitude, westLongitude),
        LatLng(southLatitude, eastLongitude),
        LatLng(northLatitude, eastLongitude),
        LatLng(northLatitude, westLongitude),
    )

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    /**
     * Shows the successor and, deliberately, leaves the predecessor alone.
     *
     * See the class KDoc: with no delivery barrier there is nothing to prove the successor painted,
     * so hiding the predecessor here is the one action that could put bare basemap on screen.
     */
    /**
     * Shows the successor, then hides the predecessor.
     *
     * **Owner decision 2026-09-08, overriding this class's original choice.** It used to leave the
     * predecessor up and wait for `onVerification` to remove it, on the reasoning that a hidden
     * `GroundOverlay` produces no pixels and so nothing here can prove the successor painted before
     * it is shown. That reasoning is unchanged and still correct; what was wrong was the cost it
     * assumed. The KDoc budgeted "a few hundred milliseconds" of double fog, and measured on the
     * device it lasted until the NEXT camera movement - screen mean 50.8 against the tile path's
     * 118.8, with the arm's own holes showing the predecessor's fog through them. An arm nobody can
     * see the boundary of cannot be judged, which is the entire purpose of these builds.
     *
     * Three things keep the exposure as small as the decision allows:
     * 1. The successor is shown FIRST and the predecessor is hidden only if that succeeded, so a
     *    failed reveal leaves both layers exactly as they were and stays fail-closed.
     * 2. It HIDES rather than removes. The predecessor's overlay and backdrop are still installed
     *    and still hold their bitmap, so `remove` remains the only thing that destroys anything.
     * 3. The backdrop is hidden with the image, because a surround left visible under a hidden
     *    image is the 15k shape again - fog claiming ground it no longer draws.
     *
     * The residual risk is the one the owner accepted: the SDK may not have painted the successor
     * in the frame this returns, and that frame would then show basemap the user has not explored.
     */
    override fun reveal(generationId: Long, previousGenerationId: Long?): Boolean {
        val entry = installed[generationId] ?: return false
        val revealed = try {
            // The backdrop first. Both orders leave one frame imperfect and only this one errs
            // towards MORE fog: showing the surround before the image can over-fog the middle for
            // a frame, while showing the image first would show bare ground around it.
            entry.backdrop.forEach { polygon -> polygon.isVisible = true }
            entry.overlay.transparency = VISIBLE_FOG_TRANSPARENCY
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!revealed) return false
        previousGenerationId?.let { previous -> hideGeneration(previous) }
        return true
    }

    /**
     * Takes one generation off the screen without destroying it.
     *
     * Best-effort per object rather than all-or-nothing: a predecessor half hidden is strictly less
     * fog than one fully shown, and there is nothing useful to do with a failure here - the layer is
     * already superseded and `remove` will take it either way.
     */
    private fun hideGeneration(generationId: Long) {
        val entry = installed[generationId] ?: return
        runCatching { entry.overlay.transparency = HIDDEN_FOG_TRANSPARENCY }
        entry.backdrop.forEach { polygon -> runCatching { polygon.isVisible = false } }
    }

    override fun remove(generationId: Long): Boolean {
        val entry = installed[generationId] ?: return true
        val removed = try {
            entry.overlay.remove()
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!removed) return false
        entry.backdrop.forEach { polygon -> removeSafely(polygon) }
        installed.remove(generationId)
        entry.bitmap.recycle()
        return true
    }

    override fun covers(generationId: Long?, visibleCorners: List<GeoPoint>): Boolean {
        val entry = installed[generationId ?: return false] ?: return false
        return entry.extent.covers(visibleCorners)
    }

    override fun release() {
        installed.values.forEach { entry ->
            try {
                entry.overlay.remove()
            } catch (_: Exception) {
                // Nothing to do: the surface is going away with it.
            } catch (_: LinkageError) {
                // As above.
            }
            entry.backdrop.forEach { polygon -> removeSafely(polygon) }
            entry.bitmap.recycle()
        }
        installed.clear()
    }

    override fun describe(): String {
        val sizes = installed.entries.joinToString(",") { (id, entry) ->
            "$id:${entry.width}x${entry.height}+backdrop${entry.backdrop.size}"
        }
        return "mosaic gens=[$sizes] refusals=$refusals last=$lastRefusal"
    }

    private fun refuse(reason: String): Boolean {
        refusals += 1
        lastRefusal = reason
        return false
    }

    /**
     * A dateline mosaic is refused rather than anchored the long way round.
     *
     * `FogPocMosaic` leaves its longitude bounds unwrapped on purpose, so a mosaic that crosses the
     * antimeridian reports an east longitude above 180 (or a west below -180). `LatLng` normalises
     * into [-180, 180), so handing those straight to `LatLngBounds` would produce a quad spanning
     * almost the whole world in the wrong direction - a silently misplaced fog image rather than a
     * failure. Refusing fails the generation and raises the cover.
     */
    /**
     * What this surface actually fogs: the anchored image, and nothing outside it.
     *
     * **Measured, after this returned the wrong answer.** The first version handed back
     * `FogBackdropGeometry.extent(mosaic)`, which is what the shipped tile path answers with - and
     * it is right there, because the tile path also installs a SURROUND backdrop reaching most of a
     * world beyond the mosaic, so ground outside the tile rectangle is still fogged. Prototype A
     * installs one image and no backdrop, so it inherited a promise it does not keep. The
     * `V03-011` zoom-out control caught it: a pinch out of 2.32 levels left the cover down and put
     * **89.344%** of the map on screen as bare basemap over unexplored ground, across 7 of 9
     * judged in-window frames. Nothing else in the spike would have shown it - every other metric
     * simply looked better.
     *
     * So the extent is the image's own rectangle. A viewport that leaves it is not covered, the
     * cover rises, and a new generation is planned at the camera the gesture reached, exactly as
     * the tile path does. That is what makes arm 2's claim the narrow one it was always stated as:
     * an in-extent gesture builds nothing, and one that leaves still covers.
     *
     * [FogSurroundExtent.wrapsWorld] is false unconditionally. A mosaic wide enough to span the
     * world would set it true through [FogBackdropGeometry], because the tile path installs a world
     * copy on each side and so has no east or west edge to fall off; one image has both edges
     * however wide it is.
     */
    private fun imageExtent(mosaic: FogTileMosaic): FogSurroundExtent {
        val bounds = mosaic.bounds
        return FogSurroundExtent(
            centerLongitude = (bounds.westLongitude + bounds.eastLongitude) / 2.0,
            halfWorlds = (bounds.eastLongitude - bounds.westLongitude) / 2.0 /
                FogBackdropGeometry.WORLD_LONGITUDE_SPAN,
            northNormalizedY = WebMercator.normalizedY(bounds.northLatitude),
            southNormalizedY = WebMercator.normalizedY(bounds.southLatitude),
            wrapsWorld = false,
        )
    }

    private fun boundsOrNull(mosaic: FogTileMosaic): LatLngBounds? {
        val bounds = mosaic.bounds
        if (bounds.westLongitude < -MAX_LONGITUDE || bounds.eastLongitude > MAX_LONGITUDE) {
            return null
        }
        if (bounds.southLatitude > bounds.northLatitude) return null
        return try {
            LatLngBounds(
                LatLng(bounds.southLatitude, bounds.westLongitude),
                LatLng(bounds.northLatitude, bounds.eastLongitude),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    internal companion object {
        /**
         * The mosaic's own pixels, scaled down to fit a budget, aspect preserved.
         *
         * `V03-011` section 15e: composing at the tiles' own resolution is 3840x4096 = 62.9 MB for
         * the measured 15x16 completion, and the argument against it is sampling rather than memory
         * - that image covers a 1080x2400 readback, so it is a six-fold oversample the SDK discards
         * resampling down, and at a tilted pose a ground-anchored image cannot match a perspective
         * projection at more than one depth anyway. The budget below is one full-screen ARGB_8888
         * bitmap of the measured viewport, which is the same allocation `GoogleFogSnapshotProver`
         * already makes and recycles up to ten times per proof.
         *
         * A mosaic already under the budget is rasterised at its own size: scaling UP would invent
         * nothing and cost memory.
         */
        fun targetSizeFor(mosaic: FogTileMosaic): Pair<Int, Int> {
            val width = mosaic.mask.width
            val height = mosaic.mask.height
            val pixels = width.toLong() * height.toLong()
            if (pixels <= MAX_RASTER_PIXELS) return width to height
            val scale = Math.sqrt(MAX_RASTER_PIXELS.toDouble() / pixels.toDouble())
            return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
        }

        /** 1080 x 2400, the viewport every measurement in section 14 was taken on. */
        const val MAX_RASTER_PIXELS = 1080L * 2400L

        /** The tile path's own z-order and opacities, so both arms present identically. */
        const val OLD_OVERLAY_Z = 0f
        const val NEW_OVERLAY_Z = 1f
        const val HIDDEN_FOG_TRANSPARENCY = 1f
        const val VISIBLE_FOG_ALPHA = 184
        const val VISIBLE_FOG_TRANSPARENCY = 1f - VISIBLE_FOG_ALPHA / 255f

        /**
         * Between the demoted predecessor and this generation's own image.
         *
         * The SDK draws shapes above ground overlays whatever their z, so this cannot hide the
         * image it belongs to - and it does not need to: the guard is the complement of that
         * image's rectangle and the two are disjoint by construction. What it does cover for a
         * moment is the PREDECESSOR's revealed ground, during the overlap the fail-closed reveal
         * leaves open. That is more fog rather than less, which is the direction this surface is
         * allowed to be wrong in.
         */
        const val BACKDROP_Z = 0.5f
        const val TRANSPARENT = 0

        /** The widest longitude span handed to one `Polygon`; see [splitForGooglePolygon]. */
        const val MAX_POLYGON_RING_DEGREES = 90.0

        const val MAX_LONGITUDE = 180.0
    }
}
