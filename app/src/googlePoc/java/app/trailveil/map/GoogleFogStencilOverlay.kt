package app.trailveil.map

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import app.trailveil.data.map.ViewportBounds
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogTilePngCodec
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay

/** The harness twin: true when the owner has selected the stencil arm. */
internal fun googleFogStencilActive(): Boolean = GoogleFogCoverageArm.screenStencil

/**
 * `V03-013` arm `screenStencil`: the owner's own design, built to be felt rather than argued about.
 *
 * Fill the screen with fog, then remove the revealed circles from it. The fog is anchored to the
 * SCREEN rather than to the ground, so panning costs nothing - there is no tile set to leave and
 * no cover to raise. That is the whole attraction, and the whole risk sits in one question: does
 * what this draws stay registered with what the SDK draws underneath it?
 *
 * **Two deliberate differences from the shipping app the owner tested (`AdSchl2E/open_world`),
 * both aimed at that question.**
 *
 * 1. **The projection is read every frame, not cached from a camera callback.** That app keeps
 *    `mapCenter`/`mapZoom` from `onCameraMove` and repaints when they change, so its overlay is
 *    always at least one callback behind. This asks `map.projection` inside the draw, which is the
 *    freshest answer available to anything outside the SDK. If a visible lag survives that, it is
 *    not fixable from here at all - and knowing which of those two worlds we are in is the point
 *    of building this.
 * 2. **It uses the SDK's own projection rather than reimplementing Web Mercator.** That app's
 *    helper takes only centre, zoom and size, so it has no tilt or bearing term and simply breaks
 *    when the map is rotated or tilted. `toScreenLocation` carries the full camera, so a tilted
 *    pose is drawn correctly or not at all - it cannot be silently wrong.
 *
 * **This arm is fail-OPEN by construction and that is not hidden.** Every other surface in this app
 * fogs by drawing fog that is always present, with holes inside it; this one leaves the basemap
 * untouched and puts a layer over it, so a frame where the layer does not draw is a frame of bare
 * ground. There is no proof machinery here and there cannot be: the snapshot prover compares the
 * screen against a canonical mask, and this surface has no mask. It is a measurement fixture in the
 * harness build type, absent from anything published, and section 15k is the standing reason to
 * distrust how good its other numbers look.
 */
@Composable
internal fun GoogleFogStencilOverlay(
    map: GoogleMap?,
    fogRuntime: FogRuntime?,
    modifier: Modifier = Modifier,
) {
    // THE GATE, and it belongs here rather than only at the call site.
    //
    // Without it this composable draws a full-screen coat of fog on EVERY arm, on top of whatever
    // canonical fog that arm already draws - two coats at alpha 184 composite to 0.92 rather than
    // 0.72, so every Google arm reads as "darker, with a second layer", and any hands-on comparison
    // made in that state is comparing the wrong thing. The call site's own comment warns that
    // suppressing the arm per-call-site is how a site gets missed and the two fogs end up drawn
    // over each other; this is the guard that makes that warning structural instead of advisory.
    //
    // It also has to be the FIRST statement, before the null checks: returning here means the
    // per-frame `withFrameNanos` loop and the 400 ms reveal query never start, so a non-stencil arm
    // pays nothing for this layer existing in the build.
    if (!googleFogStencilActive()) return
    if (map == null || fogRuntime == null) return

    // Geometry and projection move at completely different rates, so they are read on different
    // clocks: a Room read per frame would be absurd, and a projection read per query would be the
    // lag this arm exists to avoid.
    // Kept as SEGMENTS rather than flattened points, because the canonical fog reveals the capsule
    // swept between consecutive points in a segment - not the points. Flattening loses the only
    // information that says which pairs are joined.
    var reveals by remember(map) { mutableStateOf<List<List<LatLng>>>(emptyList()) }
    var frame by remember(map) { mutableIntStateOf(0) }

    LaunchedEffect(map, fogRuntime) {
        while (true) {
            val visible = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull()
            if (visible != null) {
                val margin = REVEAL_QUERY_MARGIN_DEGREES
                val bounds = runCatching {
                    ViewportBounds(
                        south = (visible.southwest.latitude - margin).coerceIn(-85.0, 85.0),
                        north = (visible.northeast.latitude + margin).coerceIn(-85.0, 85.0),
                        west = visible.southwest.longitude,
                        east = visible.northeast.longitude,
                    )
                }.getOrNull()
                if (bounds != null) {
                    reveals = runCatching {
                        fogRuntime.viewportCoordinator.readRevealedSegments(bounds)
                            .map { segment ->
                                segment.points.map { point ->
                                    LatLng(point.latitude, point.longitude)
                                }
                            }
                    }.getOrDefault(reveals)
                }
            }
            delay(GEOMETRY_REFRESH_MILLIS)
        }
    }

    // The redraw clock. Compose only recomposes when state changes, and the camera is not state
    // this layer owns, so the frame counter is what makes "read the projection every frame"
    // actually happen. Deliberately unconditional: a gesture is exactly when it must not stop.
    LaunchedEffect(map) {
        while (true) {
            withFrameNanos { }
            frame++
        }
    }

    val fog = FogTilePngCodec.DEFAULT_FOG_COLOR
    val fogColor = Color(
        red = fog.red,
        green = fog.green,
        blue = fog.blue,
        alpha = FOG_ALPHA,
    )

    Canvas(
        modifier = modifier.graphicsLayer(
            // Without an offscreen layer, BlendMode.Clear would punch through to whatever is
            // behind this composable rather than through this composable's own fog.
            compositingStrategy = CompositingStrategy.Offscreen,
        ),
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frame
        val projection = runCatching { map.projection }.getOrNull() ?: return@Canvas
        drawRect(color = fogColor)

        val radiusPx = revealRadiusPixels(projection, size.width)
        if (radiusPx <= 0f) return@Canvas
        val feathered = radiusPx * FEATHER_SCALE
        reveals.forEach { segment ->
            var previous: Offset? = null
            segment.forEach inner@{ point ->
                val screen = runCatching { projection.toScreenLocation(point) }.getOrNull()
                    ?: return@inner
                val centre = Offset(screen.x.toFloat(), screen.y.toFloat())
                val offscreen = centre.x < -feathered || centre.y < -feathered ||
                    centre.x > size.width + feathered || centre.y > size.height + feathered

                // THE CAPSULE, and it is not a refinement. The canonical mask sweeps a disc ALONG
                // the segment; drawing only the discs leaves fog between any two points further
                // apart than the radius. Measured against the tile path on the same seeded track,
                // the point-only version was missing 118,023 of 256,293 revealed pixels - 46% of
                // the explored ground - because a walk's points are not always close together.
                val start = previous
                if (start != null && !(offscreen && isOffscreen(start, size, feathered))) {
                    drawLine(
                        color = Color.Black,
                        start = start,
                        end = centre,
                        strokeWidth = radiusPx * 2f,
                        cap = StrokeCap.Round,
                        blendMode = BlendMode.Clear,
                    )
                }
                previous = centre

                if (offscreen) return@inner
                // A radial gradient rather than a hard circle, because the edge is free here: the
                // same paint that removes the fog can taper it. Drawn after the capsule so the
                // taper is what a reader sees wherever points are dense, which on a real track is
                // nearly everywhere. `V03-002` owns the boundary's look; this is only the shape it
                // comes out as on this arm.
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = FEATHER_STOPS,
                        center = centre,
                        radius = feathered,
                    ),
                    radius = feathered,
                    center = centre,
                    blendMode = BlendMode.Clear,
                )
            }
        }
    }
}

/** Both ends off-screen on the same side means the capsule cannot cross the viewport. */
private fun isOffscreen(point: Offset, size: Size, margin: Float): Boolean =
    point.x < -margin || point.y < -margin || point.x > size.width + margin ||
        point.y > size.height + margin

/**
 * The reveal radius in screen pixels, measured through the SDK rather than recomputed.
 *
 * Projecting the centre and a point one radius north and taking the distance keeps this honest
 * under tilt: at the top of a tilted viewport a metre is worth fewer pixels than at the bottom, and
 * a formula in zoom and latitude cannot say that. It is still one number for the whole screen, so
 * it is measured at the centre, which is where the reader is looking.
 */
private fun revealRadiusPixels(
    projection: com.google.android.gms.maps.Projection,
    screenWidth: Float,
): Float {
    val centre = projection.visibleRegion.latLngBounds.center
    val north = LatLng(
        (centre.latitude + REVEAL_RADIUS_METRES / METRES_PER_DEGREE_LATITUDE).coerceIn(-85.0, 85.0),
        centre.longitude,
    )
    val a = runCatching { projection.toScreenLocation(centre) }.getOrNull() ?: return 0f
    val b = runCatching { projection.toScreenLocation(north) }.getOrNull() ?: return 0f
    val dx = (a.x - b.x).toFloat()
    val dy = (a.y - b.y).toFloat()
    val radius = kotlin.math.sqrt(dx * dx + dy * dy)
    // A sub-pixel reveal is invisible, and inflating it - which the app the owner tested does, by
    // up to 1000x - would draw more revealed ground than exists. Drawing nothing is the honest
    // answer at that scale and the fail-CLOSED one.
    return if (radius < MIN_VISIBLE_RADIUS_PX || radius > screenWidth) 0f else radius
}

private const val FOG_ALPHA = 184
private const val REVEAL_RADIUS_METRES = 25.0
private const val METRES_PER_DEGREE_LATITUDE = 111_320.0
private const val REVEAL_QUERY_MARGIN_DEGREES = 0.01
private const val GEOMETRY_REFRESH_MILLIS = 400L
private const val MIN_VISIBLE_RADIUS_PX = 0.75f
private const val FEATHER_SCALE = 1.25f

/**
 * Solid out to exactly the reveal radius, then tapering beyond it.
 *
 * **The first stop was 0.60, and with [FEATHER_SCALE] of 1.25 that put the fully-cleared radius at
 * 18.75 m against the canonical fog's 25 m - every revealed shape a quarter short in every
 * direction.** The taper is meant to soften the edge, not to move it inward: at 0.80 the solid core
 * is `0.80 * 1.25 = 1.0` of the true radius, and the gradient spends its length OUTSIDE that,
 * which is the side where erring costs a soft halo rather than unrevealed ground.
 */
private val FEATHER_STOPS = arrayOf(
    0.0f to Color.Black,
    0.80f to Color.Black,
    0.87f to Color.Black.copy(alpha = 0.85f),
    0.93f to Color.Black.copy(alpha = 0.45f),
    0.97f to Color.Black.copy(alpha = 0.15f),
    1.0f to Color.Transparent,
)
