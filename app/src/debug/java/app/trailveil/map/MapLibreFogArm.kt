package app.trailveil.map

import android.content.Context
import androidx.core.content.edit
import app.trailveil.map.fog.FogViewportCoordinator

/**
 * `V03-013`: the fog arms this provider's harness can select.
 *
 * This provider starts from a better place than the other one, and the arm list reflects that.
 * Its fog is already an anchored mosaic that survives a gesture, so there is no cover to remove
 * and nothing here is putting out a fire; what these arms buy is how far the camera can travel
 * before the mosaic runs out.
 *
 * [mosaicPaddingTiles] is the same lever in a different place: `FogViewportTileGrid.around`
 * already took a padding and `FogViewportCoordinator.render` simply never passed one, so the
 * legacy 3x3 is padding 1. Raising it renders MORE fog around the same centre, which makes each
 * arm a superset of the one below it - it cannot expose ground the default would have covered.
 */
internal enum class MapLibreFogArm(
    /** Stable key written to preferences; never localise or renumber it. */
    val id: String,
    /** What the settings screen shows. */
    val label: String,
    /** Distance in tiles from the centre tile; 1 is the shipped 3x3. */
    val mosaicPaddingTiles: Int,
) {
    /** The shipped mosaic, and the control every other row is read against. */
    BASELINE("baseline", "Baseline (3x3)", FogViewportCoordinator.DEFAULT_MOSAIC_PADDING_TILES),

    /** 5x5: 25 tiles for 9, so roughly 2.8x the render for one extra tile of travel per axis. */
    MOSAIC_5("mosaic5", "Mosaic 5x5", 2),

    /** 7x7: 49 tiles. The point at which the cost is worth watching as closely as the benefit. */
    MOSAIC_7("mosaic7", "Mosaic 7x7", 3),
    ;

    companion object {
        private const val PREFERENCES = "trailveil-fog-arm"
        private const val KEY = "arm"

        /** The arm a fresh install runs, so an unconfigured harness measures the shipped design. */
        val DEFAULT = BASELINE

        fun fromId(id: String?): MapLibreFogArm = entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * Read where the fog runtime is built rather than at process start.
         *
         * Unlike the other provider's selector there is no ordering problem to solve here: the
         * value is a constructor argument to [FogViewportCoordinator], so whatever is stored when
         * the runtime is constructed is what that runtime uses for its life. Changing it still
         * needs a restart, because the runtime is built once per process.
         */
        fun stored(context: Context): MapLibreFogArm = fromId(
            context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY, null),
        )

        /** `commit = true` because the settings screen offers to quit the process straight after. */
        fun store(context: Context, arm: MapLibreFogArm) {
            context.applicationContext
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit(commit = true) { putString(KEY, arm.id) }
        }
    }
}
