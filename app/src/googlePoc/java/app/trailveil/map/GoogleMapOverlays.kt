package app.trailveil.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.View
import androidx.core.graphics.createBitmap
import app.trailveil.R
import app.trailveil.map.fog.FogProbeExclusionZone
import app.trailveil.map.fog.FogScreenRect
import app.trailveil.map.fog.FogOverlayVisibilityGate
import app.trailveil.map.fog.GeoPoint
import app.trailveil.map.fog.WebMercator
import app.trailveil.map.fog.fogProbeExclusionZoneForScreenRect
import app.trailveil.map.fog.splitTrackAtAntimeridian
import app.trailveil.map.fog.wholeWorldFogProbeExclusionZone
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlin.math.ceil
import kotlin.math.max

internal data class GoogleMapMarkerObservation(
    val position: LatLng,
    val visible: Boolean,
    val title: String?,
    val snippet: String?,
)

internal data class GoogleMapPolylineObservation(
    val points: List<LatLng>,
    val color: Int,
    val width: Float,
    val alpha: Int,
    val zIndex: Float,
    val geodesic: Boolean,
    val visible: Boolean,
)

/** Snapshot of actual SDK overlay properties, exposed only through the optional test seam. */
internal data class GoogleMapOverlayObservation(
    val currentMarker: GoogleMapMarkerObservation?,
    val trackMarkers: List<GoogleMapMarkerObservation>,
    val polylines: List<GoogleMapPolylineObservation>,
)

/**
 * Optional real-SDK observation seam used by googlePoc instrumentation. It is null by default and
 * never participates in map rendering; the permanent history tests set it only while they inspect
 * a real MainActivity detail composition.
 */
internal object GoogleMapOverlayTestHooks {
    @Volatile var onObservation: ((GoogleMapOverlayObservation) -> Unit)? = null
}

/**
 * Google map overlays owned by one hosted composition.
 *
 * The renderer keeps the marker and track objects installed but invisible until the matching
 * canonical fog generation has passed its screen proof. Keeping the objects alive makes location
 * updates cheap and, more importantly, lets a stale proof callback change neither geometry nor
 * visibility: [revealForGeneration] is the only method that makes them visible.
 */
internal class GoogleMapOverlays(
    private val map: GoogleMap,
    private val mapView: View,
    density: Float,
    private val onVisibilityChanged: ((Boolean) -> Unit)? = null,
    private val onObservationChanged: ((GoogleMapOverlayObservation) -> Unit)? = null,
) {
    private val currentIcon = createDotIcon(
        density = density,
        radius = CURRENT_DOT_RADIUS_PX,
        stroke = CURRENT_DOT_STROKE_PX,
        fill = Color.rgb(0x15, 0x65, 0xC0),
    )
    private val trackIcon = createDotIcon(
        density = density,
        radius = TRACK_DOT_RADIUS_PX,
        stroke = TRACK_DOT_STROKE_PX,
        fill = Color.rgb(0x6A, 0x1B, 0x9A),
    )
    private var currentPoint: GeoPoint? = null
    private var track: MapTrackOverlay? = null
    private var currentMarker: Marker? = null
    private val trackMarkers = ArrayList<Marker>()
    private val trackLines = ArrayList<Polyline>()
    private val renderedTrackPaths = ArrayList<List<LatLng>>()
    private val visibilityGate = FogOverlayVisibilityGate()
    private val visibleGeneration: Long?
        get() = visibilityGate.visibleGeneration
    private var released = false

    /** Updates geometry without changing the proof gate. */
    fun update(
        currentLocation: GeoPoint?,
        trackOverlay: MapTrackOverlay?,
    ): Boolean {
        if (released) return false
        val geometryChanged = currentPoint != currentLocation || track != trackOverlay
        if (currentPoint != currentLocation) {
            currentPoint = currentLocation
            updateCurrentMarker()
        }
        if (track != trackOverlay) {
            track = trackOverlay
            rebuildTrack()
        }
        publishObservation()
        return geometryChanged
    }

    /** Hides all map overlays until a proof for a later generation is accepted. */
    fun hideUntilProof(): Boolean {
        if (released) return false
        val wasVisible = visibleGeneration != null
        visibilityGate.hide()
        setVisibility(false)
        return wasVisible
    }

    /**
     * Makes overlays visible only for a non-stale, already-installed generation.
     *
     * Generation ids are monotonic within a composition. A callback for an older generation must
     * not resurrect overlays after a newer generation has actually been proven. A failed handover
     * may roll back to the previously proven generation, so hiding alone does not advance this
     * floor.
     */
    fun revealForGeneration(generation: Long) {
        if (released || generation < 0L) return
        if (!visibilityGate.revealForProvenGeneration(generation)) return
        setVisibility(true)
    }

    /** Fog-free detail maps retain the existing visible track behavior. */
    fun showWithoutFogProof() {
        if (released) return
        visibilityGate.showWithoutFogProof()
        setVisibility(true)
    }

    /**
     * Returns screen footprints for the overlays currently visible above the fog.
     *
     * Every rectangle is expanded by one screen pixel before projection. The proof samples a
     * strong candidate as a 3x3 neighbourhood, so a probe centre one pixel outside the unexpanded
     * marker/line rectangle can still be occluded by the overlay. If the projection is unavailable
     * or crosses an unrepresentable boundary, returning a whole-world zone forces the proof caller
     * to hide overlays and re-plan instead of weakening the oracle.
     */
    fun exclusionZonesForProof(): List<FogProbeExclusionZone> {
        if (released || visibleGeneration == null) return emptyList()
        val width = mapView.width
        val height = mapView.height
        if (width <= 0 || height <= 0) return listOf(wholeWorldFogProbeExclusionZone())
        // At low zoom a viewport can contain more than one wrapped world copy. The SDK may draw
        // an overlay copy on either side of the seam, while a single geographic rectangle cannot
        // describe both screen footprints. Force the safe hide/re-plan path for that pose.
        val worldWidthPx = runCatching {
            WORLD_TILE_SIZE_PX * Math.pow(2.0, map.cameraPosition.zoom.toDouble())
        }.getOrNull()
        if (worldWidthPx != null && worldWidthPx.isFinite() && worldWidthPx <= width.toDouble()) {
            return listOf(wholeWorldFogProbeExclusionZone())
        }

        val rectangles = ArrayList<FogScreenRect>()
        currentMarker?.let { marker ->
            screenPoint(marker.position)?.let { point ->
                val radius =
                    (CURRENT_DOT_RADIUS_PX + CURRENT_DOT_STROKE_PX / 2f) * densityScale()
                rectangles += FogScreenRect(
                    left = point.x - radius,
                    top = point.y - radius,
                    right = point.x + radius,
                    bottom = point.y + radius,
                )
            } ?: return listOf(wholeWorldFogProbeExclusionZone())
        }
        renderedTrackPaths.forEach { path ->
            if (path.isEmpty()) return@forEach
            val points = path.mapNotNull(::screenPoint)
            if (points.size != path.size) {
                rectangles += FogScreenRect(0.0, 0.0, width.toDouble(), height.toDouble())
                return@forEach
            }
            // Google PolylineOptions.width is already in screen pixels; only the bitmap marker
            // radii are density-scaled.
            val halfWidth = TRACK_LINE_WIDTH_PX / 2.0
            rectangles += FogScreenRect(
                left = points.minOf(Point::x).toDouble() - halfWidth,
                top = points.minOf(Point::y).toDouble() - halfWidth,
                right = points.maxOf(Point::x).toDouble() + halfWidth,
                bottom = points.maxOf(Point::y).toDouble() + halfWidth,
            )
        }
        trackMarkers.forEach { marker ->
            screenPoint(marker.position)?.let { point ->
                val radius =
                    (TRACK_DOT_RADIUS_PX + TRACK_DOT_STROKE_PX / 2f) * densityScale()
                rectangles += FogScreenRect(
                    left = point.x - radius,
                    top = point.y - radius,
                    right = point.x + radius,
                    bottom = point.y + radius,
                )
            } ?: rectangles.add(
                FogScreenRect(0.0, 0.0, width.toDouble(), height.toDouble()),
            )
        }

        return rectangles.mapNotNull { rectangle ->
            fogProbeExclusionZoneForScreenRect(
                rectangle = rectangle.inflate(STRONG_PROBE_RADIUS_PX),
                viewportWidth = width,
                viewportHeight = height,
            ) { x, y ->
                runCatching { map.projection.fromScreenLocation(Point(x.toInt(), y.toInt())) }
                    .getOrNull()
                    ?.let(::toGeoPoint)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        currentMarker?.removeSafely()
        currentMarker = null
        trackMarkers.forEach { marker -> marker.removeSafely() }
        trackMarkers.clear()
        trackLines.forEach { line -> line.removeSafely() }
        trackLines.clear()
        renderedTrackPaths.clear()
        visibilityGate.release()
    }

    private fun updateCurrentMarker() {
        val point = currentPoint?.toGoogleLatLng()
        if (point == null) {
            currentMarker?.remove()
            currentMarker = null
            return
        }
        val marker = currentMarker ?: runCatching {
            map.addMarker(
                MarkerOptions()
                    .position(point)
                    .icon(currentIcon)
                    .anchor(0.5f, 0.5f)
                    .visible(false),
            )
        }.getOrNull()?.also { created -> currentMarker = created }
        marker?.position = point
        marker?.isVisible = visibleGeneration != null
    }

    private fun rebuildTrack() {
        trackLines.forEach { line -> line.removeSafely() }
        trackLines.clear()
        trackMarkers.forEach { marker -> marker.removeSafely() }
        trackMarkers.clear()
        renderedTrackPaths.clear()

        track?.segments.orEmpty().forEach { segment ->
            datelineSafePaths(segment).forEach { path ->
                if (path.size >= 2) {
                    val line = runCatching {
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(path)
                                .color(
                                    Color.argb(
                                        (TRACK_LINE_ALPHA * 255f).toInt(),
                                        0x6A,
                                        0x1B,
                                        0x9A,
                                    ),
                                )
                                .width(TRACK_LINE_WIDTH_PX)
                                .zIndex(Float.MAX_VALUE)
                                .geodesic(false),
                        )
                    }.getOrNull()
                    if (line != null) {
                        trackLines += line
                        renderedTrackPaths += path
                    }
                } else if (path.size == 1) {
                    val marker = runCatching {
                        map.addMarker(
                            MarkerOptions()
                                .position(path.single())
                                .icon(trackIcon)
                                .anchor(0.5f, 0.5f)
                                .visible(false),
                        )
                    }.getOrNull()
                    if (marker != null) {
                        trackMarkers += marker
                        renderedTrackPaths += path
                    }
                }
            }
        }
        setVisibility(visibleGeneration != null)
    }

    private fun setVisibility(show: Boolean) {
        mapView.setTag(R.id.map_overlay_visible, show)
        onVisibilityChanged?.invoke(show)
        currentMarker?.isVisible = show
        trackMarkers.forEach { marker -> marker.isVisible = show }
        trackLines.forEach { line -> line.isVisible = show }
        publishObservation()
    }

    private fun publishObservation() {
        val callback = onObservationChanged
        if (callback == null && GoogleMapOverlayTestHooks.onObservation == null) return
        val observation = try {
            GoogleMapOverlayObservation(
                currentMarker = currentMarker?.let(::observeMarker),
                trackMarkers = trackMarkers.map(::observeMarker),
                polylines = trackLines.map(::observePolyline),
            )
        } catch (_: Exception) {
            return
        } catch (_: LinkageError) {
            return
        }
        try {
            callback?.invoke(observation)
        } catch (_: Exception) {
            // Optional test observation must never affect map rendering.
        } catch (_: LinkageError) {
            // Same isolation for provider/test linkage failures.
        }
        try {
            GoogleMapOverlayTestHooks.onObservation?.invoke(observation)
        } catch (_: Exception) {
            // The real-SDK test seam is best effort and cannot affect production rendering.
        } catch (_: LinkageError) {
            // Same isolation for provider/test linkage failures.
        }
    }

    private fun observeMarker(marker: Marker): GoogleMapMarkerObservation =
        GoogleMapMarkerObservation(
            position = marker.position,
            visible = marker.isVisible,
            title = marker.title,
            snippet = marker.snippet,
        )

    private fun observePolyline(polyline: Polyline): GoogleMapPolylineObservation =
        GoogleMapPolylineObservation(
            points = polyline.points.toList(),
            color = polyline.color,
            width = polyline.width,
            alpha = Color.alpha(polyline.color),
            zIndex = polyline.zIndex,
            geodesic = polyline.isGeodesic,
            visible = polyline.isVisible,
        )

    private fun screenPoint(position: LatLng): Point? = runCatching {
        map.projection.toScreenLocation(position)
    }.getOrNull()

    private fun densityScale(): Double = mapView.resources.displayMetrics.density.toDouble()

    private fun toGeoPoint(position: LatLng): GeoPoint? = runCatching {
        GeoPoint(position.latitude, WebMercator.wrapLongitude(position.longitude))
    }.getOrNull()

    private fun GeoPoint.toGoogleLatLng(): LatLng? {
        if (latitude !in -90.0..90.0) return null
        // The Maps SDK canonicalizes an exact +180 endpoint to -180 when the actual Marker/
        // Polyline points are read back. Keep a microscopic interior epsilon at this SDK boundary
        // so each split path remains short in the SDK's own observable representation.
        val longitude = when (longitude) {
            180.0 -> DATELINE_ENDPOINT_EPSILON
            -180.0 -> -DATELINE_ENDPOINT_EPSILON
            else -> WebMercator.wrapLongitude(longitude)
        }
        return runCatching {
            LatLng(latitude, longitude)
        }.getOrNull()
    }

    /** Converts one stored segment into paths that never bridge a gap or the antimeridian. */
    private fun datelineSafePaths(segment: List<GeoPoint>): List<List<LatLng>> {
        val validRuns = ArrayList<List<GeoPoint>>()
        var run = ArrayList<GeoPoint>()
        fun flush() {
            if (run.isNotEmpty()) validRuns += run
            run = ArrayList()
        }
        segment.forEach { rawPoint ->
            if (rawPoint.latitude !in -90.0..90.0) {
                flush()
                return@forEach
            }
            run += rawPoint
        }
        flush()
        return validRuns
            .flatMap(::splitTrackAtAntimeridian)
            .mapNotNull { path ->
                path.mapNotNull { point -> point.toGoogleLatLng() }.takeIf { points ->
                    points.isNotEmpty() && points.size == path.size
                }
            }
    }

    private fun createDotIcon(
        density: Float,
        radius: Float,
        stroke: Float,
        fill: Int,
    ): BitmapDescriptor {
        val scaledRadius = max(1f, radius * density)
        val scaledStroke = max(1f, stroke * density)
        val size = ceil((scaledRadius + scaledStroke) * 2f + 2f).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawCircle(center, center, scaledRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = scaledStroke
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, scaledRadius, paint)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun Marker.removeSafely() {
        runCatching { remove() }
    }

    private fun Polyline.removeSafely() {
        runCatching { remove() }
    }

    private companion object {
        const val FOG_FREE_GENERATION = Long.MIN_VALUE
        const val CURRENT_DOT_RADIUS_PX = 7f
        const val CURRENT_DOT_STROKE_PX = 3f
        const val TRACK_DOT_RADIUS_PX = 5f
        const val TRACK_DOT_STROKE_PX = 2f
        const val TRACK_LINE_WIDTH_PX = 5f
        const val TRACK_LINE_ALPHA = 0.9f
        const val STRONG_PROBE_RADIUS_PX = 1.0
        const val WORLD_TILE_SIZE_PX = 256.0
        const val DATELINE_ENDPOINT_EPSILON = 179.999999
    }
}
