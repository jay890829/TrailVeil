package app.trailveil.map

/**
 * The harness twin: it may put the fog on `V03-011` arm 2's anchored image, and only when asked.
 *
 * Read once per binding, like [googleFogCoverageProfile], so one surface cannot straddle two arms.
 * Set [GoogleFogCoverageArm.mosaicOverlay] BEFORE launching the Activity.
 *
 * Null means "use the shipped surface": [GoogleCanonicalFogSurfaceBinding] treats a null installer
 * as the tile path, so this seam can never leave a map unfogged - only differently fogged.
 */
internal fun googleFogOverlayInstaller(
    context: GoogleFogSurfaceContext,
): GoogleFogOverlayInstaller? = if (GoogleFogCoverageArm.mosaicOverlay) {
    GoogleFogMosaicOverlayInstaller(map = context.map)
} else {
    null
}
