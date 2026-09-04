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
    private var rightToLeft = false
    private var released = false
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { apply() }

    /**
     * Registration follows the window, not this object's lifetime.
     *
     * A `ViewTreeObserver` read from an ATTACHED view is the window's - shared, long-lived; one read
     * from a DETACHED view is a throwaway the view carries until it next attaches. Registering in
     * `init` and removing in [release] gets that backwards at the end, because Compose detaches the
     * `AndroidView` before it runs `onDispose`: by then `mapView.viewTreeObserver` hands back a fresh
     * floating observer, `isAlive` is true on it, the removal succeeds against the wrong object, and
     * the real listener stays on the window's observer holding this placement - and the MapView with
     * it - for as long as the window lives. Not hypothetical on this surface: the terminal-failure
     * path in `V02-007` section 5b swaps the map out while the activity lives on.
     *
     * Attaching and detaching in step with the view fixes it, because `onViewDetachedFromWindow` runs
     * while the AttachInfo is still set, so the observer removed there is the one that was added.
     */
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (released) return
            view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            apply()
        }

        override fun onViewDetachedFromWindow(view: View) {
            removeLayoutListener(view)
            // The SDK rebuilds its decorations across a detach, so a cached view would be stale.
            compass = null
        }
    }

    init {
        mapView.addOnAttachStateChangeListener(attachListener)
        if (mapView.isAttachedToWindow) {
            mapView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }
    }

    /**
     * The screen's requested insets, in pixels from the map's own top and end edges, plus the
     * direction that decides which edge "end" is.
     *
     * [rightToLeft] comes from the CALLER - the composition's `LocalLayoutDirection`, the same one
     * that resolves `Alignment.End` for the menu button this compass is placed under - and not from
     * the SDK view's own resolved direction. The two can disagree: the SDK is free to lay its
     * decorations out LTR inside a mirrored screen, and reading the view would then put the compass
     * on the opposite edge from the control it is meant to sit beneath.
     */
    fun setInsets(topPx: Int, endPx: Int, rightToLeft: Boolean) {
        if (released) return
        topInsetPx = topPx
        endInsetPx = endPx
        this.rightToLeft = rightToLeft
        apply()
    }

    fun release() {
        if (released) return
        released = true
        mapView.removeOnAttachStateChangeListener(attachListener)
        removeLayoutListener(mapView)
        // Left where it was rather than reset: this composition is going away, and a compass that
        // jumps back to the corner it came from on the way out is worse than one that stays put.
        compass = null
    }

    private fun removeLayoutListener(view: View) {
        val observer = view.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(layoutListener)
    }

    private fun apply() {
        if (released) return
        val view = compass ?: findCompass(mapView)?.also { found -> compass = found } ?: return
        if (view.width <= 0 || view.height <= 0) return
        val map = mapView.boundsOnScreen()
        if (map.isEmpty) return
        val bounds = view.boundsOnScreen()
        // The END edge, taken from the caller's layout direction - not from wherever the SDK left
        // the view, and not from the view's own resolved direction.
        //
        // Measured on the API 36 Play Store image, the Maps SDK puts its compass at the top START
        // corner. `V02-005-design.md` recorded that as a parity delta against the other actual's
        // top-END placement, carried rather than closed; the owner accepted closing it on
        // 2026-09-04. `compassEndInset` names an END inset, and the screen spends it stacking the
        // compass beneath its own menu button, which is an END-corner control. Honouring the
        // parameter literally puts both actuals' compasses in the same corner; a first version of
        // this pinned the compass to whichever edge it was already nearest, which honoured the top
        // inset and left the compass under the widest notice card.
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
