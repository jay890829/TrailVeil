package app.trailveil.map

/**
 * Optional real-SDK observation seam, in the harness build type only.
 *
 * The permanent googlePoc history tests inspect a real `MainActivity` composition that they do not
 * construct, so they cannot reach [GoogleMapOverlays]' `onObservationChanged` parameter - which is
 * why a process-global exists at all. `V02-013` verifier, 2026-09-07: it used to live in
 * `src/google`, shared by both Google build types, so the published APK carried a mutable static
 * whose only purpose is instrumentation. Null by default and unreachable across processes, so
 * nothing was wrong with the shipped build; it was scaffolding in a public artifact all the same,
 * and this project already refuses that for the harness Activities.
 *
 * Now the seam is declared once per build type - the idiom [ProductionMapProvider] uses - and the
 * release build's version below is a function returning null, so no test-hooks object is compiled
 * into it at all.
 */
internal object GoogleMapOverlayTestHooks {
    @Volatile
    var onObservation: ((GoogleMapOverlayObservation) -> Unit)? = null
}

/** Read once per publication, so the guard and the invoke cannot see different values. */
internal fun googleMapOverlayObservationSeam(): ((GoogleMapOverlayObservation) -> Unit)? =
    GoogleMapOverlayTestHooks.onObservation
