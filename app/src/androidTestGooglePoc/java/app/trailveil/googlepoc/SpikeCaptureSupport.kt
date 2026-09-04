package app.trailveil.googlepoc

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.get
import androidx.core.view.isVisible
import app.trailveil.map.fog.FogTilePngCodec
import com.google.android.gms.maps.MapView
import kotlin.math.abs

/**
 * `V02-005` stage 3, SP1/SP2 shared pixel machinery: full-bitmap fog scans with exclusion
 * rects, patch calibration, and the watermark/compass locator chains whose observed strategy is
 * itself SP2 evidence.
 */
object SpikeCaptureSupport {
    const val EXCLUSION_MARGIN_PX = 8

    /** The strategy name a locator reports when it found nothing and returned a guessed rect. */
    const val FALLBACK_STRATEGY = "fallbackRect"

    /** Any pixel inside the whole palette family (all 63 signatures + placeholder) +- video/GL
     *  tolerance. Labels/roads/POIs differ from this window by >= 40 per channel. */
    fun isFogFamily(pixel: Int, tolerance: Int = 6): Boolean {
        val base = FogTilePngCodec.DEFAULT_FOG_COLOR
        val maxOffset = 3 * FogTilePngCodec.SIGNATURE_CHANNEL_STEP
        val red = android.graphics.Color.red(pixel)
        val green = android.graphics.Color.green(pixel)
        val blue = android.graphics.Color.blue(pixel)
        return red in (base.red - tolerance)..(base.red + maxOffset + tolerance) &&
            green in (base.green - tolerance)..(base.green + maxOffset + tolerance) &&
            blue in (base.blue - tolerance)..(base.blue + maxOffset + tolerance)
    }

    data class PixelTally(val analyzedPx: Int, val excludedPx: Int, val nonFogPx: Int)

    fun countNonFog(bitmap: Bitmap, exclusions: List<Rect>, stridePx: Int = 2): PixelTally {
        var analyzed = 0
        var excludedCount = 0
        var nonFog = 0
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                if (exclusions.any { it.contains(x, y) }) {
                    excludedCount += 1
                } else {
                    analyzed += 1
                    if (!isFogFamily(row[x])) nonFog += 1
                }
                x += stridePx
            }
            y += stridePx
        }
        return PixelTally(analyzedPx = analyzed, excludedPx = excludedCount, nonFogPx = nonFog)
    }

    /**
     * Median per-channel delta of five 32x32 patches (center + four quarter intersections)
     * against the installed generation colour. A single label-bearing patch cannot abort the
     * capture — it is counted by [countNonFog] as a LEAK instead.
     */
    fun calibrationDelta(bitmap: Bitmap, generation: Long): Int {
        val expected = FogTilePngCodec.colorForGeneration(generation)
        val anchors = listOf(
            bitmap.width / 2 to bitmap.height / 2,
            bitmap.width / 4 to bitmap.height / 4,
            bitmap.width * 3 / 4 to bitmap.height / 4,
            bitmap.width / 4 to bitmap.height * 3 / 4,
            bitmap.width * 3 / 4 to bitmap.height * 3 / 4,
        )
        val deltas = anchors.map { (cx, cy) ->
            var maxDelta = 0
            var samples = 0
            var redSum = 0L
            var greenSum = 0L
            var blueSum = 0L
            for (y in (cy - 16).coerceAtLeast(0) until (cy + 16).coerceAtMost(bitmap.height)) {
                for (x in (cx - 16).coerceAtLeast(0) until (cx + 16).coerceAtMost(bitmap.width)) {
                    val pixel = bitmap[x, y]
                    redSum += android.graphics.Color.red(pixel)
                    greenSum += android.graphics.Color.green(pixel)
                    blueSum += android.graphics.Color.blue(pixel)
                    samples += 1
                }
            }
            if (samples == 0) return@map Int.MAX_VALUE
            maxDelta = maxOf(
                abs((redSum / samples).toInt() - expected.red),
                abs((greenSum / samples).toInt() - expected.green),
                abs((blueSum / samples).toInt() - expected.blue),
            )
            maxDelta
        }.sorted()
        return deltas[deltas.size / 2]
    }

    /** Count of pixels in [rect] whose max channel delta from the generation colour exceeds 25 —
     *  the logo-variant-agnostic "something visibly not fog renders here" corroboration. */
    fun fogDeltaCount(bitmap: Bitmap, rect: Rect, generation: Long): Int {
        val expected = FogTilePngCodec.colorForGeneration(generation)
        var count = 0
        for (y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(bitmap.height)) {
            for (x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(bitmap.width)) {
                val pixel = bitmap[x, y]
                val delta = maxOf(
                    abs(android.graphics.Color.red(pixel) - expected.red),
                    abs(android.graphics.Color.green(pixel) - expected.green),
                    abs(android.graphics.Color.blue(pixel) - expected.blue),
                )
                if (delta > 25) count += 1
            }
        }
        return count
    }

    data class LocatorObservation(
        val found: Boolean,
        val strategy: String,
        val viewClass: String,
        val hierarchyPath: String,
        val boundsInMapViewPx: Rect,
        val boundsNormalized: RectF,
        val visible: Boolean,
    )

    /** Chain: tag "GoogleWatermark" -> contentDescription "Google" -> bottom-left ImageView scan
     *  -> dp-scaled default rect (strategy=fallbackRect). Main thread only. */
    fun locateWatermark(mapView: MapView): LocatorObservation {
        findByTag(mapView, "GoogleWatermark")?.let { view ->
            return observation(mapView, view, "tag")
        }
        findBy(mapView) { view ->
            view.contentDescription?.toString()?.contains("Google", ignoreCase = true) == true
        }?.let { view -> return observation(mapView, view, "contentDesc") }
        findBy(mapView) { view ->
            view is ImageView && view.drawable != null && view.isShown &&
                view.boundsIn(mapView).let { rect ->
                    rect.centerY() > mapView.height * 2 / 3 && rect.centerX() < mapView.width / 2
                }
        }?.let { view -> return observation(mapView, view, "imageScan") }
        val density = mapView.resources.displayMetrics.density
        val rect = Rect(
            (4 * density).toInt(),
            mapView.height - (28 * density).toInt(),
            (100 * density).toInt(),
            mapView.height - (4 * density).toInt(),
        )
        return LocatorObservation(
            found = false,
            strategy = FALLBACK_STRATEGY,
            viewClass = "none",
            hierarchyPath = "none",
            boundsInMapViewPx = rect,
            boundsNormalized = rect.normalized(mapView),
            visible = false,
        )
    }

    /**
     * Chain: tag "GoogleMapCompass" -> top-corner ImageView -> dp-scaled default rect.
     *
     * EITHER top corner, in both untagged strategies. This locator was written against the SDK's
     * shipped placement, which is top-START, and scanned only that third of the width. `V02-007`
     * gave the Google surface a `GoogleCompassPlacement` that honours the neutral signature's
     * `compassEndInset` and translates the compass to the top-END corner, so a scan pinned to START
     * finds nothing on the very surface these captures audit, falls through to a START-corner
     * guess, and excludes a rectangle the compass is no longer in - leaving the real compass inside
     * the audited region, where it reads as exposed basemap.
     */
    fun locateCompass(mapView: MapView): LocatorObservation {
        findByTag(mapView, "GoogleMapCompass")?.let { view ->
            return observation(mapView, view, "tag")
        }
        findBy(mapView) { view ->
            view is ImageView && view.drawable != null && view.isShown &&
                view.boundsIn(mapView).let { rect ->
                    rect.centerY() < mapView.height / 3 &&
                        (rect.centerX() < mapView.width / 3 ||
                            rect.centerX() > mapView.width * 2 / 3)
                }
        }?.let { view -> return observation(mapView, view, "imageScan") }
        val rect = compassFallbackRects(mapView).first
        return LocatorObservation(
            found = false,
            strategy = FALLBACK_STRATEGY,
            viewClass = "none",
            hierarchyPath = "none",
            boundsInMapViewPx = rect,
            boundsNormalized = rect.normalized(mapView),
            visible = false,
        )
    }

    /**
     * Both corners the compass can occupy, START first, as dp-scaled default rects.
     *
     * Only reached when the compass was not located at all. That happens for one of two reasons and
     * the two put it in opposite corners: the SDK never laid it out (nothing is drawn, either rect
     * is harmless), or the tag went away in an SDK update - in which case `GoogleCompassPlacement`
     * failed to find it by the same tag, did not move it, and left it in the SDK's own START
     * corner, while a build whose placement DID run has it at END. A guess cannot tell those apart,
     * so callers that exclude a region exclude both.
     */
    private fun compassFallbackRects(mapView: MapView): Pair<Rect, Rect> {
        val density = mapView.resources.displayMetrics.density
        val inset = (6 * density).toInt()
        val size = (48 * density).toInt()
        val start = Rect(inset, inset, inset + size, inset + size)
        val end = Rect(
            mapView.width - inset - size,
            inset,
            mapView.width - inset,
            inset + size,
        )
        return start to end
    }

    /** Live per-capture exclusion rects (+margin); fallback rects are included deliberately so
     *  an in-surface watermark can never masquerade as a label leak. A compass that fell back
     *  contributes BOTH corners it could be in - see [compassFallbackRects]. */
    fun liveExclusionRects(mapView: MapView): Pair<List<Rect>, Boolean> {
        val watermark = locateWatermark(mapView)
        val compass = locateCompass(mapView)
        val compassGuessed = compass.strategy == FALLBACK_STRATEGY
        val fallbackUsed = watermark.strategy == FALLBACK_STRATEGY || compassGuessed
        val compassRects = if (compassGuessed) {
            compassFallbackRects(mapView).toList()
        } else {
            listOf(compass.boundsInMapViewPx)
        }
        val rects = (listOf(watermark.boundsInMapViewPx) + compassRects).map { rect ->
            Rect(
                rect.left - EXCLUSION_MARGIN_PX,
                rect.top - EXCLUSION_MARGIN_PX,
                rect.right + EXCLUSION_MARGIN_PX,
                rect.bottom + EXCLUSION_MARGIN_PX,
            )
        }
        return rects to fallbackUsed
    }

    /** Structural view-tree dump: class names, sibling indexes, visibility, alpha — no text. */
    fun dumpViewTree(view: View, path: String = view.javaClass.simpleName): List<String> =
        buildList {
            add("$path visible=${view.isVisible} alpha=${view.alpha}")
            if (view is ViewGroup) {
                repeat(view.childCount) { index ->
                    val child = view.getChildAt(index)
                    addAll(dumpViewTree(child, "$path/${child.javaClass.simpleName}[$index]"))
                }
            }
        }

    private fun observation(mapView: MapView, view: View, strategy: String): LocatorObservation {
        val bounds = view.boundsIn(mapView)
        return LocatorObservation(
            found = true,
            strategy = strategy,
            viewClass = view.javaClass.simpleName,
            hierarchyPath = pathOf(mapView, view),
            boundsInMapViewPx = bounds,
            boundsNormalized = bounds.normalized(mapView),
            visible = view.isShown && view.alpha > 0f,
        )
    }

    private fun View.boundsIn(mapView: MapView): Rect {
        val viewLocation = IntArray(2)
        val mapLocation = IntArray(2)
        getLocationOnScreen(viewLocation)
        mapView.getLocationOnScreen(mapLocation)
        val left = viewLocation[0] - mapLocation[0]
        val top = viewLocation[1] - mapLocation[1]
        return Rect(left, top, left + width, top + height)
    }

    private fun Rect.normalized(mapView: MapView): RectF = RectF(
        left.toFloat() / mapView.width,
        top.toFloat() / mapView.height,
        right.toFloat() / mapView.width,
        bottom.toFloat() / mapView.height,
    )

    private fun findByTag(root: View, tag: String): View? = root.findViewWithTag(tag)

    private fun findBy(root: View, predicate: (View) -> Boolean): View? {
        if (predicate(root)) return root
        if (root !is ViewGroup) return null
        repeat(root.childCount) { index ->
            findBy(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun pathOf(root: View, target: View): String {
        fun walk(view: View, path: String): String? {
            if (view === target) return path
            if (view !is ViewGroup) return null
            repeat(view.childCount) { index ->
                val child = view.getChildAt(index)
                walk(child, "$path/${child.javaClass.simpleName}[$index]")?.let { return it }
            }
            return null
        }
        return walk(root, root.javaClass.simpleName) ?: "unresolved"
    }
}
