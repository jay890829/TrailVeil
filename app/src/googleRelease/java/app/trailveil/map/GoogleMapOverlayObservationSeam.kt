package app.trailveil.map

/**
 * There is no observation seam in a published build.
 *
 * `V02-013` verifier, 2026-09-07. The harness build type declares the same function over a mutable
 * process-global, because its instrumentation inspects a real `MainActivity` composition it cannot
 * pass a parameter into. Nothing published needs that, so nothing published has it: this returns a
 * constant null, and the released APK carries no test-hooks object.
 *
 * Declared once per build type rather than switched on a flag, the idiom [ProductionMapProvider]
 * uses - exactly one of the two is compiled into any variant, so the release build cannot acquire
 * the other by accident.
 */
internal fun googleMapOverlayObservationSeam(): ((GoogleMapOverlayObservation) -> Unit)? = null
