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
