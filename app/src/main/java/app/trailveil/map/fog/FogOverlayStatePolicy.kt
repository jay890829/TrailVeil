package app.trailveil.map.fog

/**
 * Applies coordinator state only in the unsafe direction for proof-gated overlays.
 *
 * A healthy state publication is intentionally a no-op: an overlay that was hidden for an
 * in-flight proof must stay hidden until the proof callback for that generation is accepted. The
 * positive transition belongs to the proof owner, not to a repeated camera/lifecycle state
 * publication. An already-proven overlay also stays visible during an ordinary handover: the
 * independent view cover protects the camera while the new generation is being proved.
 */
@Suppress("UNUSED_PARAMETER")
internal fun reconcileFogOverlayCoordinatorState(
    installedGeneration: Long?,
    pendingGeneration: Long?,
    coverUp: Boolean,
    retryScheduled: Boolean,
    terminal: Boolean,
    hideOverlays: () -> Unit,
): Boolean {
    val unsafe = installedGeneration == null ||
        terminal
    if (unsafe) hideOverlays()
    return unsafe
}
