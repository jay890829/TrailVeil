package app.trailveil.map

import android.graphics.Bitmap
import app.trailveil.map.fog.FogBackdropGeometry
import app.trailveil.map.fog.FogMaskArgb
import app.trailveil.map.fog.FogMosaicTile
import app.trailveil.map.fog.FogPocMosaic
import app.trailveil.map.fog.FogSurroundExtent
import app.trailveil.map.fog.FogTileMosaic
import app.trailveil.map.fog.FogViewportCoverageRequest
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.WebMercator
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.GroundOverlay
import com.google.android.gms.maps.model.GroundOverlayOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds

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
 * 1. **The predecessor is not hidden at the reveal.** The tile path may hide it, because its
 *    delivery barrier has already proven the successor's bytes reached the SDK. There is no such
 *    barrier here - a hidden `GroundOverlay` produces no pixels at all, so nothing can prove it
 *    before it is shown. Leaving the old layer up until `onVerification` removes it means the
 *    overlap is briefly fogged twice, which looks wrong for a few hundred milliseconds. The
 *    alternative is a frame of bare basemap. The coordinator already removes the predecessor after
 *    the proof, so this is a subtraction rather than new machinery.
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
        val extent: FogSurroundExtent,
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

        installed[generationId] = Installed(
            overlay = overlay,
            bitmap = bitmap,
            extent = imageExtent(mosaic),
            width = width,
            height = height,
        )
        return true
    }

    /**
     * Shows the successor and, deliberately, leaves the predecessor alone.
     *
     * See the class KDoc: with no delivery barrier there is nothing to prove the successor painted,
     * so hiding the predecessor here is the one action that could put bare basemap on screen.
     */
    override fun reveal(generationId: Long, previousGenerationId: Long?): Boolean {
        val entry = installed[generationId] ?: return false
        return try {
            entry.overlay.transparency = VISIBLE_FOG_TRANSPARENCY
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
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
            entry.bitmap.recycle()
        }
        installed.clear()
    }

    override fun describe(): String {
        val sizes = installed.entries.joinToString(",") { (id, entry) ->
            "$id:${entry.width}x${entry.height}"
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
        const val VISIBLE_FOG_TRANSPARENCY = 1f - 184f / 255f

        const val MAX_LONGITUDE = 180.0
    }
}
