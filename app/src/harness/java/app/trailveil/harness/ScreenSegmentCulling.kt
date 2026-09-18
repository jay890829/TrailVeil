package app.trailveil.harness

/** Conservative rejection: endpoints outside different sides may still cross the viewport. */
internal fun screenSegmentOutside(
    x0: Float, y0: Float, x1: Float, y1: Float,
    width: Float, height: Float, margin: Float,
): Boolean =
    (x0 < -margin && x1 < -margin) ||
        (x0 > width + margin && x1 > width + margin) ||
        (y0 < -margin && y1 < -margin) ||
        (y0 > height + margin && y1 > height + margin)
