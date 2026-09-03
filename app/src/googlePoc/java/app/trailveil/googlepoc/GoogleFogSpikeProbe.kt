package app.trailveil.googlepoc

import android.graphics.Color
import app.trailveil.map.fog.FogTileColor
import app.trailveil.map.fog.FogTilePngCodec
import kotlin.math.abs

/**
 * `V02-005` stage 3 spike-only pixel taxonomy over the fog palette space.
 *
 * The palette is `DEFAULT_FOG_COLOR` (31,38,43) plus per-channel offsets of
 * `{0,1,2,3} * SIGNATURE_CHANNEL_STEP`; a generation colour never equals the placeholder colour
 * (its signature index is 1..63, never 0), and the minimum inter-class channel distance (4) is
 * greater than twice `VISUAL_SIGNATURE_TOLERANCE` (1), so the classes below are disjoint.
 */
enum class GoogleFogSpikePixelClass {
    /** Matches the CURRENT generation's signature colour — proven canonical fog. */
    MATCH,

    /** Matches the opaque fail-closed placeholder colour. */
    PLACEHOLDER,

    /** Inside the palette space but neither the current generation nor the placeholder. */
    STALE_PALETTE,

    /** Outside the palette space entirely — basemap-leak alarm. */
    OTHER,
}

/** Coordinate-free result of one read-only snapshot probe pass (SP6/SP8). */
data class GoogleFogSpikeProbeResult(
    val matchProbes: Int,
    val placeholderProbes: Int,
    val stalePaletteProbes: Int,
    val otherProbes: Int,
    val offScreenProbes: Int,
    val proven: Boolean,
)

object GoogleFogSpikePixelClassifier {
    fun classify(pixel: Int, currentGeneration: Long): GoogleFogSpikePixelClass {
        if (Color.alpha(pixel) != 255) return GoogleFogSpikePixelClass.OTHER
        val actual = FogTileColor(
            red = Color.red(pixel),
            green = Color.green(pixel),
            blue = Color.blue(pixel),
        )
        if (FogTilePngCodec.matchesGenerationColor(actual, currentGeneration)) {
            return GoogleFogSpikePixelClass.MATCH
        }
        val base = FogTilePngCodec.DEFAULT_FOG_COLOR
        if (
            withinTolerance(actual.red - base.red, 0) &&
            withinTolerance(actual.green - base.green, 0) &&
            withinTolerance(actual.blue - base.blue, 0)
        ) {
            return GoogleFogSpikePixelClass.PLACEHOLDER
        }
        val inPalette = paletteChannel(actual.red - base.red) &&
            paletteChannel(actual.green - base.green) &&
            paletteChannel(actual.blue - base.blue)
        return if (inPalette) {
            GoogleFogSpikePixelClass.STALE_PALETTE
        } else {
            GoogleFogSpikePixelClass.OTHER
        }
    }

    private fun paletteChannel(delta: Int): Boolean = (0..3).any { multiple ->
        withinTolerance(delta, multiple * FogTilePngCodec.SIGNATURE_CHANNEL_STEP)
    }

    private fun withinTolerance(delta: Int, expected: Int): Boolean =
        abs(delta - expected) <= FogTilePngCodec.VISUAL_SIGNATURE_TOLERANCE
}
