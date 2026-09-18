package app.trailveil.map.fog

import app.trailveil.data.map.ViewportBounds

/** Provider-neutral, immutable polygon payload. Rings are closed geographic coordinates. */
data class FogNativePolygon(
    val shell: List<GeoPoint>,
    val holes: List<List<GeoPoint>> = emptyList(),
)

data class FogNativeGeometry(
    val bounds: FogTileBounds,
    val polygons: List<FogNativePolygon>,
    /** Coordinate-free measurements; never contains canonical points or database identifiers. */
    val diagnostics: String,
)

/**
 * Optional derived geometry processor, serialized by the viewport coordinator's mutex.
 * Published builds supply null. Implementations must bound retained data and must not swallow
 * cancellation. A null render explicitly requests the existing raster fallback.
 */
interface FogNativeGeometryEngine {
    val description: String
    /**
     * Optional metadata hint, under the coordinator lock. Null means unknown/unsupported; empty
     * means no reads expected from current derived state. Must not read storage or mutate caches.
     * Actual native reads remain authoritative, including when state changes after this preview.
     */
    fun rawReadWindows(bounds: FogTileBounds, style: FogRenderStyle): List<ViewportBounds>? = null

    suspend fun render(
        bounds: FogTileBounds,
        style: FogRenderStyle,
        read: suspend (ViewportBounds) -> List<TrackSegment>,
    ): FogNativeGeometry?

    /** Optional bounded multi-partition reads; each result must equal its independent read. */
    suspend fun renderBatched(
        bounds: FogTileBounds,
        style: FogRenderStyle,
        read: suspend (ViewportBounds) -> List<TrackSegment>,
        readBatch: suspend (List<ViewportBounds>) -> List<List<TrackSegment>>,
    ): FogNativeGeometry? = render(bounds, style, read)

    fun invalidate(updates: List<FogRevealUpdate>, style: FogRenderStyle)
    fun clear()
}
