package app.trailveil.map.fog

/**
 * Generation gate for map-owned overlays.
 *
 * Only a generation that has passed the canonical fog proof may become visible. A lower,
 * late-arriving generation is rejected after a newer one has been proven; hiding does not advance
 * the floor because a failed handover is allowed to roll back to the prior proven generation.
 */
internal class FogOverlayVisibilityGate {
    var visibleGeneration: Long? = null
        private set

    private var highestProvenGeneration: Long? = null

    fun hide() {
        visibleGeneration = null
    }

    fun revealForProvenGeneration(generation: Long): Boolean {
        if (generation < 0L) return false
        val highest = highestProvenGeneration
        if (highest != null && generation < highest) return false
        highestProvenGeneration = maxOf(highest ?: generation, generation)
        visibleGeneration = generation
        return true
    }

    fun showWithoutFogProof() {
        highestProvenGeneration = null
        visibleGeneration = FOG_FREE_GENERATION
    }

    fun release() {
        highestProvenGeneration = null
        visibleGeneration = null
    }

    private companion object {
        const val FOG_FREE_GENERATION = Long.MIN_VALUE
    }
}
