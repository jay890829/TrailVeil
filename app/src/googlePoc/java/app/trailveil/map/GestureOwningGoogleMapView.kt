package app.trailveil.map

import android.content.Context
import android.view.MotionEvent
import android.view.View
import app.trailveil.R
import com.google.android.gms.maps.MapView

/**
 * Reasserts map gesture ownership inside Compose/scrolling parents on every gesture, and keeps the
 * SDK's own view tree out of accessibility traversal: the MapView is the one announced target, so
 * every child the SDK adds is hidden together with its descendants, whenever it is added.
 */
internal class GestureOwningGoogleMapView(context: Context) : MapView(context) {
    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val prior = (getTag(R.id.map_touch_down_count) as? Int) ?: 0
            setTag(R.id.map_touch_down_count, prior + 1)
        }
        return super.dispatchTouchEvent(event)
    }
}
