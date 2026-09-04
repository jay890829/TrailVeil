package app.trailveil.map

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.android.gms.maps.MapView

/**
 * Places the Maps SDK's own compass at the insets the screen asked for.
 *
 * The neutral map signature carries `compassTopInset` and `compassEndInset` because the entry
 * screen draws its menu button in the map's top-end corner and needs the map's compass out from
 * under it. The other actual behind that signature honours them with a margin call its own SDK
 * exposes. The Maps SDK has no equivalent: its documented lever is `GoogleMap.setPadding`,
 * which this repository does not use,
 * because padding moves the logical camera centre and every fog viewport calculation - the coverage
 * planner, the snapshot prover, the tile keys - is built on that centre. Moving the camera to move a
 * decoration would be a fog bug wearing a layout fix.
 *
 * So the view moves instead, exactly as `GoogleFogSafetyOverlay.positionAttributionAboveSystemBars`
 * already lifts the SDK attribution ImageView clear of the navigation bar. This translates the
 * compass and nothing else: no padding, no camera, no logo, no touch interception, and no change to
 * anything the fog reads.
 *
 * Two details the SDK forces.
 *
 * 1. **The compass has no size until the camera carries a bearing or a tilt.** It is not merely
 *    faded, the way the other actual's is; it is laid out at zero size. So placement cannot be a
 *    one-shot effect
 *    at composition - it has to re-run when the view appears, which is why this listens for layout
 *    passes rather than applying once.
 * 2. **There is no accessor for the view.** It is found by the tag the SDK sets on it. When the tag
 *    is absent the compass is left exactly where the SDK put it; a missing decoration is a cosmetic
 *    regression, and guessing at an untagged view could move something else.
 *
 * Applying is idempotent: the translation is computed from where the view currently is, so a second
 * pass over an already-placed compass moves it by zero. Translation is a draw-time property and does
 * not request layout, so re-applying inside a layout callback cannot loop.
 */
internal class GoogleCompassPlacement(
    private val mapView: MapView,
) {
    private var compass: View? = null
    private var topInsetPx = 0
    private var endInsetPx = 0
    private var released = false
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { apply() }

    init {
        mapView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    /** The screen's requested insets, in pixels from the map's own top and end edges. */
    fun setInsets(topPx: Int, endPx: Int) {
        if (released) return
        topInsetPx = topPx
        endInsetPx = endPx
        apply()
    }

    fun release() {
        if (released) return
        released = true
        if (mapView.viewTreeObserver.isAlive) {
            mapView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }
        // Left where it was rather than reset: this composition is going away, and a compass that
        // jumps back under the menu button on the way out is worse than one that stays put.
        compass = null
    }

    private fun apply() {
        if (released) return
        val view = compass ?: findCompass(mapView)?.also { found -> compass = found } ?: return
        if (view.width <= 0 || view.height <= 0) return
        val map = mapView.boundsOnScreen()
        if (map.isEmpty) return
        val bounds = view.boundsOnScreen()
        // The END edge, resolved from the layout direction, not from wherever the SDK left it.
        //
        // Measured on the API 36 Play Store image, the Maps SDK puts its compass at the top START
        // corner - `V02-005-design.md` records that as an accepted parity delta against the other
        // actual's top-END placement. `compassEndInset` names an END inset, and the screen spends it to
        // stack the compass under its own menu button, which is an END-corner control. Honouring the
        // parameter literally puts the two providers' compasses in the same corner and closes that
        // delta rather than carrying it; a first version of this pinned the compass to whichever
        // edge it was already nearest, which honoured the top inset and left the compass under the
        // notice card.
        val rightToLeft = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val deltaX = if (rightToLeft) {
            (map.left + endInsetPx) - bounds.left
        } else {
            (map.right - endInsetPx) - bounds.right
        }
        val deltaY = (map.top + topInsetPx) - bounds.top
        if (deltaX != 0) view.translationX += deltaX.toFloat()
        if (deltaY != 0) view.translationY += deltaY.toFloat()
    }

    private fun findCompass(view: View): View? {
        if (view.tag == COMPASS_TAG) return view
        if (view !is ViewGroup) return null
        repeat(view.childCount) { index ->
            findCompass(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun View.boundsOnScreen(): Rect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    private companion object {
        /** The tag the Maps SDK sets on its compass view; the spike locator matches on it too. */
        const val COMPASS_TAG = "GoogleMapCompass"
    }
}
