package app.trailveil.map

import android.content.Context
import androidx.core.content.edit
import app.trailveil.map.fog.FogViewportCoordinator

/**
 * Historical regression controls on MapLibre's fixed centre-tile grid; the final default is native 5x5.
 * Base 3x3 is the smaller native control, not Google's viewport-shaped floor planner.
 * Both 5x5 arms actually publish the larger extent; their ring can buy camera travel.
 */
internal enum class MapLibreFogArm(
    val id: String,
    val label: String,
    val mosaicPaddingTiles: Int,
    val trackVector: Boolean = false,
) {
    TRACK_VECTOR_FLOOR("trackVectorFloor", "Track vector (base 3x3)",
        FogViewportCoordinator.DEFAULT_MOSAIC_PADDING_TILES, trackVector = true),
    // Keep the old id and extent: existing MapLibre trackVector users selected native 5x5.
    TRACK_VECTOR_RING("trackVector", "Track vector (ring 2, 5x5)", 2, trackVector = true),
    RING_2("mosaic5", "Ring 2 (raster, 5x5)", 2),
    ;

    companion object {
        private const val PREFERENCES = "trailveil-fog-arm"
        private const val KEY = "arm"
        /** Final owner-selected scheme. Historical enum values remain only for regression probes. */
        val DEFAULT = TRACK_VECTOR_RING

        /** Old, unknown and absent saved choices all resolve to the final scheme. */
        fun fromId(@Suppress("UNUSED_PARAMETER") id: String?): MapLibreFogArm = DEFAULT

        /** No preference read: even a malformed retired value cannot affect startup. */
        fun stored(@Suppress("UNUSED_PARAMETER") context: Context): MapLibreFogArm = DEFAULT

        /** Legacy preference fixture writer; this no longer changes the running or next-launch scheme. */
        fun store(context: Context, arm: MapLibreFogArm) {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit(commit = true) { putString(KEY, arm.id) }
        }
    }
}
