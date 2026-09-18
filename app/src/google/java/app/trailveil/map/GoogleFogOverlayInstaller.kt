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
 * Methods run on Main except [prepare] and [hasPreparedNativeGeometry], both on the render worker.
 */
internal interface GoogleFogOverlayInstaller {
    /** This preparer reads raw canonical points after the same attempt's raster read. */
    val reusesCanonicalPoints: Boolean get() = false

    /** Opt-in only: preparation can decide whether the unused padded raster is needed. */
    val canPrepareFromRequiredMasks: Boolean get() = false

    /** Worker-side answer after prepare; unknown or raster payloads retain the full raster. */
    fun hasPreparedNativeGeometry(generationId: Long): Boolean = false

    /** The installed representation needs the existing cover/retirement path before replacement. */
    fun requiresCoverForHandover(installedGenerationId: Long): Boolean = false

    /** Optional elapsed-realtime deadline for a newly revealed surface's first snapshot request.
     * Scheduling hint only: reaching it never counts as proof. Already settled/tile surfaces use 0.
     */
    fun snapshotNotBeforeMillis(generationId: Long): Long = 0L

    /** Optional background preparation, inside the generation's render/cancellation budget. */
    suspend fun prepare(
        generationId: Long,
        coverage: FogViewportCoverageRequest,
        tiles: List<FogMosaicTile>,
    ) = Unit

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
     * The safety cover has just risen, or a refuted generation stays installed beneath it: take
     * every layer this installer has revealed off the screen, so the interval reads as ONE coat
     * of fog - the cover, at the fog's own colour and alpha, over bare basemap - and not fog
     * stacked on fog. The binding hides the tile path's overlays on the same edge (V02-012
     * design 2); this is the installer's half of that rule.
     *
     * Measured on the API 36 AVD (2026-09-10, same viewport and fixture): with the mosaic image
     * or the native polygons left beneath the cover the screen read (41,52,58) against the fog's
     * own (68,88,97); the tile path read (68,88,97) under its cover. The owner read the first as
     * "the cover is darker than the fog" - it is not the cover, it is the second coat.
     *
     * Best effort and never fail-open: a layer that stays is a second coat of fog, and the cover
     * itself is what keeps unproven ground no clearer than fog. An installer that cannot hide a
     * layer (hiding a holed polygon crashes the SDK; recorded in the vector installer) removes it
     * instead. The coordinator's later `remove` of such a generation then finds nothing and
     * reports success - unless the removal itself failed, in which case the installer keeps
     * reporting that generation as a failed removal, the same fail-closed contract as [remove]
     * (a stray layer above later generations would otherwise fail every proof until the cover
     * deadline). `covers` answers false for a removed generation, which only asks for the
     * rebuild the raised cover already implies; a host-start re-proof of it cannot lower the
     * cover, and the rebuild comes from the pending generation or the next idle, with the cover
     * up throughout.
     */
    fun hideBeneathCover()

    /**
     * Whether [generationId]'s published fog still covers [visibleCorners].
     *
     * This is the surround term the tile path answers with `published >= predicted` over tile keys.
     * An installer that publishes no tile keys answers it geometrically instead, from the extent of
     * what it actually anchored - which is the one term of the surround test a `GroundOverlay`
     * genuinely takes away. False for an unknown or null generation.
     */
    fun covers(generationId: Long?, visibleCorners: List<GeoPoint>): Boolean

    /** False only for an installed geometry payload whose detail is independent of tile zoom. */
    fun requiresRasterResolution(generationId: Long?): Boolean = true

    /**
     * False for an installer that never reads the published PNG tiles. Its masks still arrive
     * through [prepare] and [attach] and the proof still samples them; only the provider-facing
     * encode is dead weight, so the binding publishes an empty key set and keeps the adapter's
     * currency gate alone. Deliberately, the adapter's per-key budget rejections (render version,
     * entry and byte caps) then no longer apply to this installer either: nothing of its output is
     * held by the adapter, and the render itself is still bounded by the coverage profile's key
     * ceiling. True by default: the tile path draws through the provider.
     */
    val publishesMaskTiles: Boolean get() = true

    /** Releases every layer and any bitmap it holds. */
    fun release()

    /** Installer-specific diagnostics, appended to the binding's gates line. */
    fun describe(): String
}
