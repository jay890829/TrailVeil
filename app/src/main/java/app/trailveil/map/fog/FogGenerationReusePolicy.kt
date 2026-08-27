package app.trailveil.map.fog

/** Pure state rule for deciding whether an idle render may reuse its pending generation. */
object FogGenerationReusePolicy {
    fun canReuse(
        activeGenerationId: Long?,
        installedGenerationId: Long?,
        adapterIsCurrent: Boolean,
    ): Boolean =
        activeGenerationId != null &&
            adapterIsCurrent &&
            activeGenerationId != installedGenerationId
}
