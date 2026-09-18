package app.trailveil.map

import android.graphics.Bitmap
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogNativeGeometry

internal fun nativeTrackFogEnabled(): Boolean = false

internal fun currentMapLibreFogRenderMode(): MapLibreFogRenderMode = MapLibreFogRenderMode(false, false)

internal class PreparedNativeFog private constructor()
@Suppress("UNUSED_PARAMETER")
internal suspend fun prepareNativeFog(geometry: FogNativeGeometry): PreparedNativeFog =
    error("native fog is not enabled in this build")

/**
 * A distributed build draws its fog as the raster it always has.
 *
 * `V03-013`'s vector arm is a measurement fixture and lives in `src/debug` alone, because
 * `src/mapLibre` is compiled by `release` as well. This twin answers false unconditionally and
 * **cannot name what it would have installed**: no GeoJSON source, no fill layer, no mask
 * decomposition is compiled into this variant.
 */
@Suppress("UNUSED_PARAMETER")
internal fun installVectorFogIfArmed(
    style: org.maplibre.android.maps.Style,
    sourceId: String,
    layerId: String,
    bounds: FogTileBounds,
    bitmap: () -> Bitmap,
    belowLayerId: String,
    enabled: Boolean = false,
): Boolean = false

@Suppress("UNUSED_PARAMETER")
internal fun installNativeFog(
    style: org.maplibre.android.maps.Style,
    sourceId: String,
    layerId: String,
    geometry: FogNativeGeometry,
    prepared: PreparedNativeFog,
    belowLayerId: String,
): Unit = error("native fog is not enabled in this build")
