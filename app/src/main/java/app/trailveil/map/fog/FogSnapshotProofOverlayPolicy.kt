package app.trailveil.map.fog

/** Result of preparing a visual proof plan whose probes are hidden by map overlays. */
data class FogProofPlanPreparation(
    val canProve: Boolean,
    val overlaysHidden: Boolean,
)

/**
 * Preserves the bounded-exclusion rule at the proof boundary.
 *
 * A plan with zone-blocked tiles is not a failed proof and is never allowed to pass. The caller
 * must hide the overlays and obtain a fresh plan. The callback reports whether that state change
 * happened; a false result leaves the proof bounded and fail-closed instead of retrying forever.
 */
fun prepareFogProofPlan(
    plan: FogSnapshotVisualProbePlan,
    hideOverlays: () -> Boolean,
): FogProofPlanPreparation {
    if (plan.isProvable()) {
        return FogProofPlanPreparation(canProve = true, overlaysHidden = false)
    }
    val hidden = hideOverlays()
    return FogProofPlanPreparation(canProve = false, overlaysHidden = hidden)
}
