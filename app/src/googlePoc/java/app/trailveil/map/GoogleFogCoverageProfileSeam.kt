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

    /** Restores the shipped profile. Every arm test must call this in an `@After`. */
    fun reset() {
        profile = GoogleFogCoverageProfile.DEFAULT
    }
}

/** Read once per binding, so one surface cannot straddle two arms. */
internal fun googleFogCoverageProfile(): GoogleFogCoverageProfile = GoogleFogCoverageArm.profile
