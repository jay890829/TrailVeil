package app.trailveil.map

import app.trailveil.map.fog.FogMosaicTile
import app.trailveil.map.fog.FogViewportCoverageRequest
import app.trailveil.map.fog.GeoPoint

/**
 * How a published fog generation is put on the map, so `V03-011` arm 2 can put it there differently.
 *
 * The shipped surface serves a generation as 256x256 tiles through a `TileOverlay`; prototype A
 * rasterises the same masks into one image anchored to the mosaic's ground bounds. Everything
 * before that point - the coverage plan, the render, the mask publish, the generation lifecycle,
 * the snapshot proof - is identical, which is why arm 2 is an installer rather than a second
 * binding. Reading [GoogleCanonicalFogSurfaceBinding] made that clear: only four things about a
 * `GroundOverlay` differ, and duplicating a 1350-line file to change four things would put the
 * shipped chain and the prototype's chain out of step the first time either was edited.
 *
 * **This interface names no arm and chooses nothing.** `GoogleCanonicalFogSurfaceBinding` takes one
 * as a nullable constructor argument that is null in every shipped build, and the only thing that
 * can supply a non-null one is a per-build-type seam whose `googleRelease` twin returns null
 * unconditionally and cannot name an implementation. So no prototype code, and no name belonging to
 * one, reaches a published APK - the standard `GoogleMapOverlayObservationSeam` set when it moved a
 * null-by-default hook out of `src/google` because it was scaffolding in a public artifact all the
 * same.
 *
 * Every method runs on the main thread, like the binding that calls it.
 */
internal interface GoogleFogOverlayInstaller {

    /**
     * Pushes every already-installed layer behind the one about to be attached.
     *
     * False fails the generation, exactly as the tile path's z-order failure does: an installer
     * that cannot order its layers could otherwise reveal a new generation underneath an old one.
     */
    fun demoteExisting(): Boolean

    /**
     * Installs [generationId]'s fog, not yet visible, from the masks its render produced.
     *
     * [coverage] is the plan the render was made for. [tiles] is the render's masks for that
     * plan's floor rectangle, already in row-major order - the binding owns the planner, and
     * `FogPocMosaic.compose` requires a complete rectangle, so handing over the raw mask map
     * would make every installer re-derive the same rectangle from a planner it does not have.
     * False fails the generation.
     */
    fun attach(
        generationId: Long,
        coverage: FogViewportCoverageRequest,
        tiles: List<FogMosaicTile>,
    ): Boolean

    /**
     * Shows [generationId].
     *
     * [previousGenerationId] is offered rather than commanded: whether the predecessor is hidden in
     * the same turn is the installer's decision, because it depends on what proves the successor is
     * on screen. The tile path hides it, and may, because its delivery barrier has already proven
     * the successor's bytes reached the SDK. An installer with no such barrier must not - leaving
     * the predecessor up until the snapshot proof passes is fogged twice for a moment, which fails
     * closed, where a bare frame fails open. False routes to the coordinator's reveal-failed path.
     */
    fun reveal(generationId: Long, previousGenerationId: Long?): Boolean

    /** Removes [generationId]'s layer. False means stale content may still be on screen. */
    fun remove(generationId: Long): Boolean

    /**
     * Whether [generationId]'s published fog still covers [visibleCorners].
     *
     * This is the surround term the tile path answers with `published >= predicted` over tile keys.
     * An installer that publishes no tile keys answers it geometrically instead, from the extent of
     * what it actually anchored - which is the one term of the surround test a `GroundOverlay`
     * genuinely takes away. False for an unknown or null generation.
     */
    fun covers(generationId: Long?, visibleCorners: List<GeoPoint>): Boolean

    /** Releases every layer and any bitmap it holds. */
    fun release()

    /** Installer-specific diagnostics, appended to the binding's gates line. */
    fun describe(): String
}
