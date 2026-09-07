package app.trailveil.map

import app.trailveil.map.fog.FogProbeExclusionZone
import app.trailveil.map.fog.FogRuntime
import app.trailveil.map.fog.FogViewportRender
import com.google.android.gms.maps.GoogleMap

/**
 * Everything a Google fog surface needs from its host, in one value.
 *
 * The host builds this once and hands it to [googleFogAlternativeSurface]; whatever that seam
 * declines to build, the host builds as a [GoogleCanonicalFogSurfaceBinding] from the same value.
 * So both surfaces are constructed from identical inputs by construction rather than by two call
 * sites kept in step by hand.
 *
 * **This type names no arm and selects nothing.** That is deliberate. `V03-011` arm 2's selector
 * could have been a `surfaceKind` field on `GoogleFogCoverageProfile`, but that file is in
 * `src/google`, which every Google build type compiles - so the enum value, the field and its
 * factory would all have shipped in the published APK. The repository has already ruled on that
 * shape once: `googleRelease/GoogleMapOverlayObservationSeam.kt` records moving a null-by-default,
 * cross-process-unreachable hook out of `src/google` because "it was scaffolding in a public
 * artifact all the same". A parameter carrier is not scaffolding - it says nothing about what might
 * be built from it - and the choosing lives entirely in the per-build-type seam, whose release twin
 * cannot name the alternative at all.
 */
internal data class GoogleFogSurfaceContext(
    val map: GoogleMap,
    val runtime: FogRuntime,
    val onStateChanged: (GoogleCanonicalFogState) -> Unit,
    val onTerminalFailure: () -> Unit,
    val onFogFailure: (Throwable) -> Unit,
    val onFogRendered: ((FogViewportRender) -> Unit)?,
    val onProofObserved: (GoogleFogProofObservation) -> Unit,
    val exclusionZonesForProof: () -> List<FogProbeExclusionZone>,
    val onUnprovableProofPlan: () -> Boolean,
    val onProofAccepted: (Long) -> Unit,
    val installFaultForTesting: (() -> Unit)?,
)

/** The shipped surface, built from the same inputs any alternative would have been built from. */
internal fun GoogleFogSurfaceContext.canonicalBinding(): GoogleCanonicalFogSurfaceBinding =
    GoogleCanonicalFogSurfaceBinding(
        map = map,
        runtime = runtime,
        onStateChanged = onStateChanged,
        onTerminalFailure = onTerminalFailure,
        onFogFailure = onFogFailure,
        onFogRendered = onFogRendered,
        onProofObserved = onProofObserved,
        exclusionZonesForProof = exclusionZonesForProof,
        onUnprovableProofPlan = onUnprovableProofPlan,
        onProofAccepted = onProofAccepted,
        installFaultForTesting = installFaultForTesting,
    )
