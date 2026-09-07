package app.trailveil.map

/**
 * A published build renders exactly what it can see.
 *
 * `V03-011` arm 1 needs the Google surface to pre-render a ring beyond the viewport and to reason
 * about the surround without it. That is a measurement fixture, so it exists only in the harness
 * build type: this returns the shipped constants and nothing published carries a mutable knob that
 * could change how much fog a real user's map renders.
 *
 * Declared once per build type rather than switched on a flag - the idiom [ProductionMapProvider]
 * and [googleMapOverlayObservationSeam] use - so exactly one of the two is compiled into any
 * variant and the release build cannot acquire the other by accident.
 */
internal fun googleFogCoverageProfile(): GoogleFogCoverageProfile =
    GoogleFogCoverageProfile.DEFAULT
