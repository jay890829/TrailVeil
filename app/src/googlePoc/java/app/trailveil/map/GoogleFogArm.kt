package app.trailveil.map

import android.content.Context
import androidx.core.content.edit

/**
 * `V03-013`: the fog arms a PERSON can select, as opposed to the ones a test sets.
 *
 * `V03-011` measured arms 1 and 2 through instrumentation and produced every figure in that
 * ledger, but [GoogleFogCoverageArm] defaults to the shipped behaviour and is only ever written by
 * a test, so the harness APK launched by hand has always run `baseline`. This enum is the list of
 * configurations that selector can hold, and [apply] is the only thing that writes them outside a
 * test.
 *
 * **Every arm here is a combination of the two independent fields [GoogleFogCoverageArm] already
 * carries.** No new mechanism: [paddingTiles] drives the coverage profile and [mosaicOverlay]
 * drives the surface. That is why [RING_2_MOSAIC] costs nothing to offer even though nothing has
 * ever run it - the two fields were always independent, and section 15k's conclusion is that they
 * are complementary rather than alternatives.
 *
 * Harness build type only. `googleRelease` compiles neither this file nor the selector it writes.
 */
internal enum class GoogleFogArm(
    /** Stable key written to preferences; never localise or renumber it. */
    val id: String,
    /** What the picker and the on-map badge show. */
    val label: String,
    /** 0 selects [GoogleFogCoverageProfile.DEFAULT]; above 0 selects `ring(p)`. */
    val paddingTiles: Int,
    /** Arm 2's anchored image instead of the shipped `TileOverlay`. */
    val mosaicOverlay: Boolean,
) {
    /** The shipped design, and the control every other row is read against. */
    BASELINE("baseline", "Baseline (shipped)", paddingTiles = 0, mosaicOverlay = false),

    /** Arm 1 at one ring - the width the AVD said was enough and the phone disproved. */
    RING_1("ring1", "Ring 1", paddingTiles = 1, mosaicOverlay = false),

    /** Arm 1 at two rings - the phone-proven width (section 14g). */
    RING_2("ring2", "Ring 2", paddingTiles = 2, mosaicOverlay = false),

    /** Arm 2 alone: removes the zoom step, buys no pan slack. */
    MOSAIC("mosaic", "Mosaic (arm 2)", paddingTiles = 0, mosaicOverlay = true),

    /**
     * Both arms at once. **Never measured, and predicted to be the best Google answer.**
     *
     * Section 15k: "one removes the zoom step, the other buys pan slack, and neither buys the
     * other's". Arm 2 stops a pinch rebuilding the world but still covers when the camera leaves
     * the image; arm 1 buys the camera room to move but cannot survive an integer zoom at any
     * width. Nothing in either implementation objects to the other being on.
     */
    RING_2_MOSAIC("ring2mosaic", "Ring 2 + Mosaic", paddingTiles = 2, mosaicOverlay = true),
    ;

    /**
     * Writes this arm into the process state a fog binding reads.
     *
     * Must run before a surface binds - [GoogleFogCoverageArm]'s own contract is that a binding
     * reads the profile once in its field initialisers and will not change arm underneath a
     * running composition. [GoogleFogArmInitializer] is what guarantees that ordering.
     */
    fun apply() {
        GoogleFogCoverageArm.profile = if (paddingTiles == 0) {
            GoogleFogCoverageProfile.DEFAULT
        } else {
            GoogleFogCoverageProfile.ring(paddingTiles)
        }
        GoogleFogCoverageArm.mosaicOverlay = mosaicOverlay
    }

    companion object {
        private const val PREFERENCES = "trailveil-fog-arm"
        private const val KEY = "arm"

        /** The arm a fresh install runs, so an unconfigured harness measures the shipped design. */
        val DEFAULT = BASELINE

        fun fromId(id: String?): GoogleFogArm =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * Read on the ContentProvider thread at startup, so it uses no coroutines and no DataStore.
         * A harness selector is not user data and deliberately does not go near the app's own
         * storage.
         */
        fun stored(context: Context): GoogleFogArm = fromId(
            context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY, null),
        )

        /**
         * Persists the choice. Applying it to a running process is the caller's problem.
         *
         * `commit = true` on purpose: the picker offers a "quit process" button immediately after
         * this returns, and an asynchronous `apply()` can lose the write to that exit. A harness
         * selector that silently keeps the previous arm is worse than a slow one.
         */
        fun store(context: Context, arm: GoogleFogArm) {
            context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit(commit = true) { putString(KEY, arm.id) }
        }
    }
}
