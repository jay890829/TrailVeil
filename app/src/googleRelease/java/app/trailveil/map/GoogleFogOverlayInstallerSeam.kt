package app.trailveil.map

/**
 * A published build puts its fog on tiles, and this file is why there is no other answer.
 *
 * `V03-011` arm 2 (prototype A) anchors one rasterised image instead. It is a measurement fixture,
 * not a product decision, so it must be physically absent from the artifact a user installs rather
 * than present and unreachable. The `googlePoc` twin of this function can return a mosaic
 * installer; this one returns null unconditionally and **cannot name an implementation at all** -
 * no class, no enum value, no `surfaceKind` field for it is compiled into `googleRelease`.
 *
 * Declared once per build type rather than switched on a flag - the idiom [ProductionMapProvider],
 * [googleMapOverlayObservationSeam] and [googleFogCoverageProfile] use - so exactly one of the two
 * is compiled into any variant and the release build cannot acquire the other by accident.
 *
 * The precedent for insisting on absence rather than unreachability is
 * `GoogleMapOverlayObservationSeam`, which records moving a null-by-default, cross-process-
 * unreachable hook out of `src/google` because it was scaffolding in a public artifact all the same.
 *
 * **Null means "use the shipped surface", never "use no surface".**
 * [GoogleCanonicalFogSurfaceBinding] treats a null installer as the tile path, so a null here can
 * never leave a real map unfogged.
 */
@Suppress("UNUSED_PARAMETER")
internal fun googleFogOverlayInstaller(
    context: GoogleFogSurfaceContext,
): GoogleFogOverlayInstaller? = null
