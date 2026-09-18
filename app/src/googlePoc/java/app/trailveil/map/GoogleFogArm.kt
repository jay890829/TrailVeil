package app.trailveil.map

import android.content.Context
import androidx.core.content.edit

/** Final scheme plus historical renderer controls; these values are not a user-facing menu. */
internal enum class GoogleFogArm(
    val id: String,
    val label: String,
    val paddingTiles: Int,
    val trackVector: Boolean = false,
) {
    TRACK_VECTOR("trackVector", "Track vector (floor only)", 0, trackVector = true),
    TRACK_VECTOR_RING("trackVectorRing", "Track vector (ring 2)", 2, trackVector = true),
    RING_2("ring2", "Ring 2 (raster)", 2),
    ;

    /** Apply the selected configuration before constructing a binding. */
    fun apply() {
        GoogleFogCoverageArm.profile = if (paddingTiles == 0) {
            GoogleFogCoverageProfile.DEFAULT
        } else {
            GoogleFogCoverageProfile.ring(paddingTiles)
        }
        // Reset every legacy switch so a removed selection cannot survive in process state.
        GoogleFogCoverageArm.mosaicOverlay = false
        GoogleFogCoverageArm.screenStencil = false
        GoogleFogCoverageArm.vectorPolygon = false
        GoogleFogCoverageArm.trackVector = trackVector
        activeArm = this
    }

    companion object {
        /** The startup-applied final scheme, or an explicit regression-probe override. */
        @Volatile var activeArm: GoogleFogArm? = null
            private set
        private const val PREFERENCES = "trailveil-fog-arm"
        private const val KEY = "arm"

        /** Final owner-selected scheme. Historical enum values remain only for regression probes. */
        val DEFAULT = TRACK_VECTOR

        /** Old, unknown and absent saved choices all resolve to the final scheme. */
        fun fromId(@Suppress("UNUSED_PARAMETER") id: String?): GoogleFogArm = DEFAULT

        /** No preference read: even a malformed retired value cannot affect startup. */
        fun stored(@Suppress("UNUSED_PARAMETER") context: Context): GoogleFogArm = DEFAULT

        /** Legacy preference fixture writer for upgrade/regression probes; startup ignores this value. */
        fun store(context: Context, arm: GoogleFogArm) {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit(commit = true) { putString(KEY, arm.id) }
        }
    }
}
