package app.trailveil.map

import app.trailveil.map.fog.FogViewportFootprint

/** Capture once per generation, including the retired mask-vector regression mode. */
internal data class MapLibreFogRenderMode(
    val nativeRequested: Boolean,
    val maskVectorRequested: Boolean,
)

/** Issued only after the complete canonical pass, including retirement and callbacks, succeeds. */
internal data class MapLibreFogIdleCertificate(
    val mapInstance: Any,
    val styleIdentity: Any,
    val runtimeIdentity: Any,
    val generation: Long,
    val revision: Long,
    val canonicalEpoch: Long,
    val slot: FogGenerationSlot,
    val footprint: FogViewportFootprint,
    val mode: MapLibreFogRenderMode,
) {
    fun matches(current: MapLibreFogIdleCertificate): Boolean =
        mapInstance === current.mapInstance && styleIdentity === current.styleIdentity &&
            runtimeIdentity === current.runtimeIdentity && generation == current.generation &&
            revision == current.revision && canonicalEpoch == current.canonicalEpoch &&
            canonicalEpoch % 2L == 0L && slot == current.slot && footprint == current.footprint && mode == current.mode
}
