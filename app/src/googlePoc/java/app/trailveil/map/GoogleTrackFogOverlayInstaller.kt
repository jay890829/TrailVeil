package app.trailveil.map

import app.trailveil.map.fog.FogMosaicTile
import app.trailveil.map.fog.FogNativeGeometry
import app.trailveil.map.fog.FogPocMosaic
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogViewportCoverageRequest
import app.trailveil.map.fog.anchoredNear
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Uses the existing vector install/proof lifecycle with geometry prepared off the UI thread. */
internal class GoogleTrackFogOverlayInstaller(
    context: GoogleFogSurfaceContext,
) : GoogleFogOverlayInstaller {
    override val reusesCanonicalPoints: Boolean get() = true
    override val canPrepareFromRequiredMasks: Boolean get() = true

    override fun hasPreparedNativeGeometry(generationId: Long): Boolean =
        prepared?.let { it.generation == generationId && it.geometry != null } == true

    private val runtime: FogRuntime = context.runtime
    private val vector = GoogleFogVectorOverlayInstaller(context.map)
    private val raster = GoogleFogMosaicOverlayInstaller(context.map)
    private val owners = LinkedHashMap<Long, GoogleFogOverlayInstaller>()

    /**
     * Generations whose vector polygons refused to leave at a native -> raster handover. The
     * vector owner has already dropped its record of them, so a later [remove] would report
     * success over polygons still on the map; these are reported as failed instead, which is the
     * coordinator's terminal path - fail-closed, not a proof that runs eval:false ten times
     * beneath stray polygons until the cover deadline.
     */
    private val failedRemovals = HashSet<Long>()
    private data class Prepared(val generation: Long, val geometry: FogNativeGeometry?, val description: String)
    @Volatile private var prepared: Prepared? = null
    private var lastMode = "awaiting"

    override suspend fun prepare(
        generationId: Long,
        coverage: FogViewportCoverageRequest,
        tiles: List<FogMosaicTile>,
    ) {
        // Preserve the compose footprint and validation without copying pixels solely for bounds.
        // The diagnostic composeMs field now measures layout; geometry wall includes mutex wait.
        val composeStartedAtNanos = System.nanoTime()
        val bounds = FogPocMosaic.layout(tiles).anchoredNear(coverage.center.longitude).bounds
        val composeMillis = (System.nanoTime() - composeStartedAtNanos) / NANOS_PER_MILLISECOND
        val geometryStartedAtNanos = System.nanoTime()
        val geometry = runtime.viewportCoordinator.renderNativeGeometry(bounds)
        val geometryWallMillis = (System.nanoTime() - geometryStartedAtNanos) / NANOS_PER_MILLISECOND
        currentCoroutineContext().ensureActive()
        val diagnostics = geometry?.diagnostics ?: "raster-fallback"
        prepared = Prepared(generationId, geometry,
            diagnostics + stageSuffix(composeMillis, geometryWallMillis, diagnostics))
    }

    /**
     * Appended AFTER the engine's closing bracket, so every reader of the `trackNative[` prefix
     * (the badge, the sharpness spike's `installerOf`) keeps parsing what it parsed before.
     * lockWaitMs is geometry wall time minus the engine's own prepareMs: the coordinator mutex
     * wait plus the partition arithmetic, which the engine cannot clock from inside the lock.
     */
    private fun stageSuffix(composeMillis: Long, geometryWallMillis: Long, diagnostics: String): String {
        val enginePrepareMillis = ENGINE_PREPARE_MS.find(diagnostics)?.groupValues?.get(1)?.toLongOrNull()
        val lockWaitMillis = enginePrepareMillis?.let { geometryWallMillis - it }
        return " composeMs=$composeMillis geomWallMs=$geometryWallMillis lockWaitMs=${lockWaitMillis ?: "-"}"
    }

    override fun demoteExisting(): Boolean = vector.demoteExisting() && raster.demoteExisting()

    override fun snapshotNotBeforeMillis(generationId: Long): Long =
        owners[generationId]?.snapshotNotBeforeMillis(generationId) ?: 0L

    override fun attach(
        generationId: Long,
        coverage: FogViewportCoverageRequest,
        tiles: List<FogMosaicTile>,
    ): Boolean {
        val ready = prepared?.takeIf { it.generation == generationId } ?: return false
        prepared = null
        val owner = if (ready.geometry == null) raster else vector
        val attached = if (ready.geometry == null) raster.attach(generationId, coverage, tiles)
            else vector.attachNative(generationId, coverage, tiles, ready.geometry)
        if (attached) {
            owners[generationId] = owner
            lastMode = ready.description
        }
        return attached
    }

    override fun reveal(generationId: Long, previousGenerationId: Long?): Boolean {
        val owner = owners[generationId] ?: return false
        val previous = previousGenerationId ?: return owner.reveal(generationId, null)
        if (owners[previous] === vector) {
            // Both native -> raster and native -> native must retire the previous polygons:
            // retaining them either occludes the image or doubles the native fog's opacity.
            // requiresCoverForHandover makes the coordinator guard this transition before render.
            // Usually the cover's rising edge already removed them. This also handles a successor
            // begun while that cover was up and another generation was visible for verification.
            if (!vector.remove(previous)) {
                failedRemovals.add(previous)
                return false
            }
            owners.remove(previous)
            return owner.reveal(generationId, null)
        }
        return owner.reveal(generationId, previous)
    }

    override fun remove(generationId: Long): Boolean {
        if (prepared?.generation == generationId) prepared = null
        if (generationId in failedRemovals) {
            // Remembered, not consumed: every later removal of this generation is a failure too,
            // so fail-closed does not rest on the first false alone.
            owners.remove(generationId)
            return false
        }
        val removed = owners.remove(generationId)?.remove(generationId) ?: true
        // A delegate that refused - a polygon that would not leave for a cover - stays a failure
        // for every later removal too, exactly like a handover stray above.
        if (!removed) failedRemovals.add(generationId)
        return removed
    }

    /**
     * Both owners: the image hides, the polygons leave (they cannot hide). [owners] is deliberately
     * left untouched: the coordinator's later `remove` of a generation the vector owner removed
     * here must still reach that owner, which is where a polygon that refused to leave is
     * remembered (its `strays`); dropping the owner entry would turn that failure into `true`.
     */
    override fun hideBeneathCover() {
        vector.hideBeneathCover()
        raster.hideBeneathCover()
    }

    override fun covers(generationId: Long?, visibleCorners: List<app.trailveil.map.fog.GeoPoint>): Boolean =
        owners[generationId]?.covers(generationId, visibleCorners) ?: false

    override fun requiresRasterResolution(generationId: Long?): Boolean = owners[generationId] !== vector

    // Native predecessors cannot be hidden in place, and leaving them visible makes the new
    // single-coat colour proof fail on two coats. Use the cover hook and the guarded reveal's
    // retirement before showing the successor. Raster handovers keep their existing behavior.
    override fun requiresCoverForHandover(installedGenerationId: Long): Boolean =
        owners[installedGenerationId] === vector

    /** Both owners draw from the masks handed to attach; neither ever asks the provider for a tile. */
    override val publishesMaskTiles: Boolean get() = false

    override fun release() {
        prepared = null
        owners.clear()
        failedRemovals.clear()
        vector.release()
        raster.release()
    }

    /** `failedRemovals=n` appears only when a predecessor's polygons refused to leave (terminal). */
    override fun describe(): String =
        "$lastMode" +
            (if (failedRemovals.isEmpty()) "" else " failedRemovals=${failedRemovals.size}") +
            " ${vector.describe()} ${raster.describe()}"

    private companion object {
        val ENGINE_PREPARE_MS = Regex("prepareMs=(\\d+)")
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
