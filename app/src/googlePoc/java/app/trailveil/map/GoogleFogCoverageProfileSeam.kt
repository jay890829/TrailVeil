package app.trailveil.map

/**
 * The `V03-011` arm selector, in the harness build type only.
 *
 * The gesture audit inspects a real composition it does not construct, so the arm cannot be a
 * constructor argument the way `installFaultForTesting` is; it has to be process state read when a
 * surface binds. Set it BEFORE launching the Activity - a binding reads the profile once, in its
 * field initialisers, and a surface already bound will not change arm underneath a running trial.
 *
 * Default is [GoogleFogCoverageProfile.DEFAULT], so a test that never touches this measures the
 * shipped behaviour. `googleRelease` declares the same function as a constant, so nothing published
 * carries this object at all.
 */
internal object GoogleFogCoverageArm {
    @Volatile
    var profile: GoogleFogCoverageProfile = GoogleFogCoverageProfile.DEFAULT

    /**
     * Arm 2 (prototype A): put the fog on one anchored image instead of a `TileOverlay`.
     *
     * Separate from [profile] because it selects a SURFACE rather than a coverage plan - arm
     * 2 renders exactly what the shipped build renders and only changes how it reaches the
     * screen. Keeping it out of `GoogleFogCoverageProfile` also keeps the arm-2 selector out
     * of `src/google`, which every Google build type compiles: the enum value, the field and
     * its factory would all have shipped in the published APK.
     */
    @Volatile
    var mosaicOverlay: Boolean = false

    /**
     * `V03-013` arm `screenStencil`: replace the ground-anchored fog with a screen-anchored one.
     *
     * Separate from [mosaicOverlay] because it is not a surface the binding installs at all - it is
     * a layer drawn beside the map in the Compose tree - and because it is the one arm that turns
     * the canonical fog OFF rather than swapping it. That makes it the only fail-open arm here, so
     * it gets its own name rather than hiding inside a surface selector.
     */
    @Volatile
    var screenStencil: Boolean = false

    /**
     * `V03-013` arm `vector`: the fog as a holed `Polygon` instead of tiles or an image.
     *
     * A surface selector like [mosaicOverlay], and exclusive with it - the binding installs one
     * installer - so the arm enum is what keeps them from both being set.
     */
    @Volatile
    var vectorPolygon: Boolean = false

    /** Restores the shipped profile and surface. Every arm test must call this in an `@After`. */
    fun reset() {
        profile = GoogleFogCoverageProfile.DEFAULT
        mosaicOverlay = false
        screenStencil = false
        vectorPolygon = false
    }
}

/** Read once per binding, so one surface cannot straddle two arms. */
internal fun googleFogCoverageProfile(): GoogleFogCoverageProfile = GoogleFogCoverageArm.profile
