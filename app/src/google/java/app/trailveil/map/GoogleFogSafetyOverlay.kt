package app.trailveil.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import app.trailveil.R
import com.google.android.gms.maps.MapView

/**
 * Non-interactive ViewOverlay guard toggled synchronously inside camera callbacks.
 *
 * Compose still publishes semantics/state, but it cannot promise that recomposition draws before
 * the SDK's next renderer frame. A drawable in MapView's own ViewOverlay is added before the camera
 * callback returns, covers SDK labels/tiles immediately, and never participates in touch dispatch.
 */
internal class GoogleFogSafetyOverlay(
    private val mapView: MapView,
) {
    private val drawable = FogCoverDrawable(Color.rgb(0x3C, 0x3D, 0x3A))
    private var visible = false
    private var released = false
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        drawable.setBounds(0, 0, right - left, bottom - top)
        positionAttributionAboveSystemBars()
    }

    init {
        mapView.setTag(R.id.map_fog_synchronous_cover_up, false)
        drawable.setBounds(0, 0, mapView.width, mapView.height)
        mapView.addOnLayoutChangeListener(layoutListener)
    }

    fun setVisible(show: Boolean) {
        if (released || visible == show) return
        visible = show
        mapView.setTag(R.id.map_fog_synchronous_cover_up, show)
        if (show) {
            drawable.setBounds(0, 0, mapView.width, mapView.height)
            mapView.overlay.add(drawable)
        } else {
            mapView.overlay.remove(drawable)
        }
        mapView.invalidate()
    }

    /**
     * Keeps the SDK-owned attribution drawable visible above the system navigation compositor.
     *
     * This moves only the SDK ImageView. It does not call GoogleMap.setPadding, change the logical
     * camera centre, alter the logo drawable, or punch a hole in the fail-closed cover. While the
     * full-surface cover is raised the logo may be temporarily hidden; after proof, the unmodified
     * SDK view is visible in the map viewport.
     */
    fun positionAttributionAboveSystemBars() {
        if (released) return
        val mapBounds = mapView.boundsOnScreen()
        val candidate = findAttribution(mapView, mapBounds) ?: return
        val navigationInset = mapView.rootWindowInsets
            ?.getInsets(WindowInsets.Type.navigationBars())
            ?.bottom
            ?: 0
        candidate.translationY = -(navigationInset + ATTRIBUTION_NAVIGATION_GUTTER_PX).toFloat()
    }

    fun release() {
        if (released) return
        setVisible(false)
        mapView.removeOnLayoutChangeListener(layoutListener)
        released = true
    }

    private fun findAttribution(view: View, mapBounds: android.graphics.Rect): ImageView? {
        if (view is ImageView && view.isShown) {
            val bounds = view.boundsOnScreen()
            val looksLikeAttribution =
                bounds.bottom >= mapBounds.bottom - ATTRIBUTION_BOTTOM_TOLERANCE_PX &&
                    bounds.left <= mapBounds.left + ATTRIBUTION_START_TOLERANCE_PX &&
                    bounds.width() in 10..300 && bounds.height() in 8..120
            if (looksLikeAttribution) return view
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                findAttribution(view.getChildAt(index), mapBounds)?.let { return it }
            }
        }
        return null
    }

    private fun View.boundsOnScreen(): android.graphics.Rect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return android.graphics.Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    private companion object {
        const val ATTRIBUTION_BOTTOM_TOLERANCE_PX = 200
        const val ATTRIBUTION_START_TOLERANCE_PX = 180
        const val ATTRIBUTION_NAVIGATION_GUTTER_PX = 8
    }
}

/** Opaque full-surface guard; no attribution cutout may expose an unproven basemap frame. */
private class FogCoverDrawable(
    private val fogColor: Int,
) : Drawable() {
    /**
     * Deliberately `also`/`it`, and deliberately not named `color`.
     *
     * This was `Paint(...).apply { this.color = color }`, which compiled to
     * `paint.setColor(paint.getColor())`: inside a receiver-scoped lambda the Paint's own `color`
     * shadows the constructor property, so the argument was stored in the field and never read. The
     * cover painted Paint's default opaque black for every surface. It stayed invisible because
     * black is still fully opaque — the fail-closed guarantee held — and only one device assertion
     * ever compared the cover's RGB.
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = fogColor }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable opacity is only a rendering hint")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
