package app.trailveil.map.fog

/**
 * At most four computed plans, never proof verdicts. Caller-confined; Google owns this on main
 * and clears it for each new proof run. Published masks must not mutate their pixel buffers.
 */
internal class FogSnapshotVisualProbePlanMemo {
    private class Entry(
        val generation: Long,
        val request: FogViewportCoverageRequest,
        val masks: Map<FogTileKey, FogPixelMask>,
        val zones: List<FogProbeExclusionZone>,
        val plan: FogSnapshotVisualProbePlan,
    )
    private val entries = arrayOfNulls<Entry>(FogProbeCandidateBank.entries.size)

    fun find(
        generation: Long,
        request: FogViewportCoverageRequest,
        masks: Map<FogTileKey, FogPixelMask>,
        zones: List<FogProbeExclusionZone>,
        bank: FogProbeCandidateBank = FogProbeCandidateBank.FORWARD,
    ): FogSnapshotVisualProbePlan? {
        val cached = entries[bank.ordinal] ?: return null
        return cached.plan.takeIf {
            cached.generation == generation && cached.request == request && cached.zones == zones &&
                cached.masks.size == masks.size && cached.masks.all { (key, mask) -> masks[key] === mask }
        }
    }

    fun remember(
        generation: Long,
        request: FogViewportCoverageRequest,
        masks: Map<FogTileKey, FogPixelMask>,
        zones: List<FogProbeExclusionZone>,
        plan: FogSnapshotVisualProbePlan,
        bank: FogProbeCandidateBank = FogProbeCandidateBank.FORWARD,
    ) {
        require(plan.candidateBank == bank)
        entries[bank.ordinal] = Entry(generation, request, masks.toMap(), zones.toList(), plan)
    }

    fun clear() { entries.fill(null) }
}
