package app.trailveil.map.fog

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A fixed RGB colour used by local fog tiles and the opaque fail-closed placeholder. */
data class FogTileColor(
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    init {
        require(red in 0..255) { "red must be in 0..255" }
        require(green in 0..255) { "green must be in 0..255" }
        require(blue in 0..255) { "blue must be in 0..255" }
    }
}

/**
 * Encodes the SDK-independent fog mask as a deterministic 8-bit RGBA PNG.
 *
 * The Google provider treats alpha zero as explored and every non-zero canonical fog value as
 * fully opaque. This prevents default Google labels/POIs from remaining legible through the
 * MapLibre-oriented semi-transparent renderer style. Unknown tiles use [opaquePlaceholder], so a
 * provider/cache race cannot uncover the Google basemap for a frame. The encoder deliberately
 * supports only 256x256 masks: Google TileProvider tiles and this adapter's canonical render
 * contract have one fixed dimension.
 */
object FogTilePngCodec {
    const val TILE_SIZE = 256
    val DEFAULT_FOG_COLOR = FogTileColor(red = 31, green = 38, blue = 43)

    /** Non-placeholder signature for the current TileOverlay palette cycle. */
    fun colorForGeneration(generation: Long): FogTileColor {
        require(generation > 0L) { "fog tile generation must be positive" }
        val index = signatureIndex(generation)
        return FogTileColor(
            red = DEFAULT_FOG_COLOR.red + (index % 4) * SIGNATURE_CHANNEL_STEP,
            green = DEFAULT_FOG_COLOR.green + ((index / 4) % 4) * SIGNATURE_CHANNEL_STEP,
            blue = DEFAULT_FOG_COLOR.blue + ((index / 16) % 4) * SIGNATURE_CHANNEL_STEP,
        )
    }

    /** The old native overlay must be removed before a palette value is reused. */
    fun generationStartsNewPaletteCycle(generation: Long): Boolean {
        require(generation > 0L) { "fog tile generation must be positive" }
        return generation > 1L && signatureIndex(generation) == 1
    }

    /** Exact shared predicate used by JVM tests and the Android snapshot oracle. */
    /**
     * V02-012 design 2. A revealed overlay is displayed at [REVEALED_FOG_ALPHA] / 255, so a fog
     * pixel on screen is `a * fog + (1 - a) * basemap`. With the basemap assumed at least
     * [BASEMAP_MINIMUM_CHANNEL] per channel (the light default style; labels and icons are drawn
     * above the overlay), each channel of a fog pixel lies in [revealedFogChannelRange], widened
     * by [REVEALED_FOG_TOLERANCE] for rounding and scaled read-backs. Bare basemap of that
     * lightness lies far above the range; the model is what the device audits use as well.
     */
    fun revealedFogChannelRange(fogChannel: Int, extraTolerance: Int = 0): IntRange {
        val alpha = REVEALED_FOG_ALPHA / 255.0
        val low = alpha * fogChannel + (1.0 - alpha) * BASEMAP_MINIMUM_CHANNEL
        val high = alpha * fogChannel + (1.0 - alpha) * 255.0
        val tolerance = REVEALED_FOG_TOLERANCE + extraTolerance
        return (Math.round(low).toInt() - tolerance).coerceAtLeast(0)..
            (Math.round(high).toInt() + tolerance).coerceAtMost(255)
    }

    fun matchesRevealedFog(actual: FogTileColor, generation: Long, extraTolerance: Int = 0): Boolean {
        val fog = colorForGeneration(generation)
        return actual.red in revealedFogChannelRange(fog.red, extraTolerance) &&
            actual.green in revealedFogChannelRange(fog.green, extraTolerance) &&
            actual.blue in revealedFogChannelRange(fog.blue, extraTolerance)
    }

    /**
     * The union over the whole signature palette, for audits that do not know the generation;
     * the safety cover (the default fog colour at the same opacity) falls inside it too.
     * [extraTolerance] widens the window for scaled or video-compressed read-backs.
     */
    fun matchesRevealedFogAnyGeneration(actual: FogTileColor, extraTolerance: Int = 0): Boolean {
        val window = anyGenerationWindows.getOrPut(extraTolerance) {
            val span = (SIGNATURE_COLOUR_COUNT_PER_CHANNEL - 1) * SIGNATURE_CHANNEL_STEP
            intArrayOf(
                revealedFogChannelRange(DEFAULT_FOG_COLOR.red, extraTolerance).first,
                revealedFogChannelRange(DEFAULT_FOG_COLOR.red + span, extraTolerance).last,
                revealedFogChannelRange(DEFAULT_FOG_COLOR.green, extraTolerance).first,
                revealedFogChannelRange(DEFAULT_FOG_COLOR.green + span, extraTolerance).last,
                revealedFogChannelRange(DEFAULT_FOG_COLOR.blue, extraTolerance).first,
                revealedFogChannelRange(DEFAULT_FOG_COLOR.blue + span, extraTolerance).last,
            )
        }
        return actual.red >= window[0] && actual.red <= window[1] &&
            actual.green >= window[2] && actual.green <= window[3] &&
            actual.blue >= window[4] && actual.blue <= window[5]
    }

    /**
     * Windows per extra tolerance, computed once: the device audits call the predicates above
     * for every pixel of a 1080x2400 frame, and six range constructions per pixel cost the
     * gesture sampler half its frames on the emulator.
     */
    private val anyGenerationWindows = java.util.concurrent.ConcurrentHashMap<Int, IntArray>()
    private val beneathCoverWindows = java.util.concurrent.ConcurrentHashMap<Int, IntArray>()

    /**
     * V02-012 design 2: everything the safety cover (the default fog colour at the fog opacity)
     * can put on screen. Per channel the cover contributes `a*c0` whatever lies beneath, so the
     * floor is the cover over black (a label) and the ceiling the cover over white:
     * `[a*c0, a*c0 + (1-a)*255]`. That contains the cover over the basemap (every overlay hidden),
     * the cover over a revealed generation (the hold's double layer, design 2b) and the cover over
     * labels; bare basemap of the assumed lightness lies above it, a bare label below it.
     */
    fun matchesBeneathCover(actual: FogTileColor, extraTolerance: Int = 0): Boolean {
        val window = beneathCoverWindows.getOrPut(extraTolerance) {
            val alpha = REVEALED_FOG_ALPHA / 255.0
            val tolerance = REVEALED_FOG_TOLERANCE + extraTolerance
            fun low(cover: Int) = Math.round(alpha * cover).toInt() - tolerance
            fun high(cover: Int) = Math.round(alpha * cover + (1.0 - alpha) * 255.0).toInt() + tolerance
            intArrayOf(
                low(DEFAULT_FOG_COLOR.red), high(DEFAULT_FOG_COLOR.red),
                low(DEFAULT_FOG_COLOR.green), high(DEFAULT_FOG_COLOR.green),
                low(DEFAULT_FOG_COLOR.blue), high(DEFAULT_FOG_COLOR.blue),
            )
        }
        return actual.red >= window[0] && actual.red <= window[1] &&
            actual.green >= window[2] && actual.green <= window[3] &&
            actual.blue >= window[4] && actual.blue <= window[5]
    }

    fun matchesGenerationColor(actual: FogTileColor, generation: Long): Boolean {
        val expected = colorForGeneration(generation)
        return kotlin.math.abs(actual.red - expected.red) <= VISUAL_SIGNATURE_TOLERANCE &&
            kotlin.math.abs(actual.green - expected.green) <= VISUAL_SIGNATURE_TOLERANCE &&
            kotlin.math.abs(actual.blue - expected.blue) <= VISUAL_SIGNATURE_TOLERANCE
    }

    private fun signatureIndex(generation: Long): Int =
        (((generation - 1L) % SIGNATURE_COLOUR_COUNT) + 1L).toInt()

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47,
        0x0d, 0x0a, 0x1a, 0x0a,
    )

    const val SIGNATURE_COLOUR_COUNT = 63L
    const val SIGNATURE_CHANNEL_STEP = 4
    const val VISUAL_SIGNATURE_TOLERANCE = 1

    /** V02-012: display alpha of a revealed fog overlay, the shared fog style's value (184). */
    val REVEALED_FOG_ALPHA: Int = FogRenderStyle().fogAlpha

    /**
     * V02-012: the darkest basemap channel value the revealed-fog window assumes (its lower
     * edge). Water on the Google light style reads about 114 in red (fog over it read 54 against
     * a floor-120 window of 57..98 on the API 36 AVD, deterministically, for three tiles of eight),
     * so the floor sits below it.
     *
     * **What the per-pixel predicate actually decides** (corrected 2026-09-06 after the
     * independent verifier enumerated it; the earlier claim here held for the red channel only).
     * Over the whole 63-colour palette the union windows are red 47..104, green 52..109, blue
     * 56..113, and the predicate is a conjunction, so a bare basemap pixel is rejected only if
     * its red is 105 or more, OR its green 110 or more, OR its blue 114 or more. Bare neutral
     * greys from 56 to 104 therefore satisfy some generation's window - Google's own label grey
     * `#616161` satisfies 48 of the 63. That is a loose per-pixel test, and it is not what the
     * guarantee rests on: [FogSnapshotVisualProbePlanner] draws several separated candidates per
     * block, a strong candidate needs 5 of its 9 neighbours, and `tallyFogProof` requires every
     * on-screen tile to reach its per-tile block quorum. A frame of bare light-style basemap
     * cannot meet that on every tile; a single dark pixel proves nothing on its own. Keep the two
     * apart when reading a probe's verdict, and keep this comment honest if either changes.
     */
    const val BASEMAP_MINIMUM_CHANNEL = 96

    /** V02-012: rounding and scaled read-back allowance on the revealed-fog window. */
    const val REVEALED_FOG_TOLERANCE = 2

    /** Signature colours per channel (the palette is this cubed, 64 values). */
    const val SIGNATURE_COLOUR_COUNT_PER_CHANNEL = 4

    fun encode(mask: FogPixelMask, color: FogTileColor = DEFAULT_FOG_COLOR): ByteArray {
        require(mask.width == TILE_SIZE && mask.height == TILE_SIZE) {
            "fog masks must be exactly ${TILE_SIZE}x$TILE_SIZE"
        }
        val rawRows = ByteArray((TILE_SIZE * 4 + 1) * TILE_SIZE)
        for (y in 0 until TILE_SIZE) {
            val rowOffset = y * (TILE_SIZE * 4 + 1)
            // PNG filter type 0. Keeping this explicit makes the bytes stable across encoders.
            rawRows[rowOffset] = 0
            for (x in 0 until TILE_SIZE) {
                val pixelOffset = rowOffset + 1 + x * 4
                rawRows[pixelOffset] = color.red.toByte()
                rawRows[pixelOffset + 1] = color.green.toByte()
                rawRows[pixelOffset + 2] = color.blue.toByte()
                rawRows[pixelOffset + 3] = if (mask.alphaAt(x, y) == 0) 0 else 0xff.toByte()
            }
        }
        return encodeRows(rawRows)
    }

    /** Builds an opaque tile without constructing a mask or using a transparent NO_TILE value. */
    fun opaquePlaceholder(color: FogTileColor = DEFAULT_FOG_COLOR): ByteArray {
        val rawRows = ByteArray((TILE_SIZE * 4 + 1) * TILE_SIZE)
        for (y in 0 until TILE_SIZE) {
            val rowOffset = y * (TILE_SIZE * 4 + 1)
            rawRows[rowOffset] = 0
            for (x in 0 until TILE_SIZE) {
                val pixelOffset = rowOffset + 1 + x * 4
                rawRows[pixelOffset] = color.red.toByte()
                rawRows[pixelOffset + 1] = color.green.toByte()
                rawRows[pixelOffset + 2] = color.blue.toByte()
                rawRows[pixelOffset + 3] = 0xff.toByte()
            }
        }
        return encodeRows(rawRows)
    }

    private fun encodeRows(rawRows: ByteArray): ByteArray {
        val compressed = Deflater(Deflater.BEST_SPEED).run {
            setInput(rawRows)
            finish()
            val output = ByteArrayOutputStream(rawRows.size)
            val buffer = ByteArray(8 * 1024)
            while (!finished()) {
                val count = deflate(buffer)
                if (count == 0 && !finished()) {
                    error("PNG deflater made no progress")
                }
                output.write(buffer, 0, count)
            }
            end()
            output.toByteArray()
        }

        return ByteArrayOutputStream(PNG_SIGNATURE.size + compressed.size + 64).apply {
            write(PNG_SIGNATURE)
            writeChunk("IHDR", byteArrayOf(
                0, 0, 1, 0, // width 256
                0, 0, 1, 0, // height 256
                8, // bit depth
                6, // truecolour with alpha
                0, // compression method
                0, // filter method
                0, // no interlace
            ))
            writeChunk("IDAT", compressed)
            writeChunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        require(type.length == 4) { "PNG chunk type must have four characters" }
        writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        write(typeBytes)
        write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }
}
