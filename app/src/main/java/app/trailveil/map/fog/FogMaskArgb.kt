package app.trailveil.map.fog

/**
 * Rasterises a fog mask of ANY size to ARGB_8888 pixels, carrying the generation signature.
 *
 * `V03-011` prototype A. [FogTilePngCodec.encode] applies exactly this rule but refuses anything
 * that is not 256x256, because a Google `TileProvider` tile has one fixed dimension. A prototype
 * that replaces the `TileOverlay` with a single anchored image needs the same rule at the mosaic's
 * dimensions, and needs it to keep writing [FogTilePngCodec.colorForGeneration] so the existing
 * snapshot proof still recognises what it is looking at.
 *
 * The `V03-011` survey recorded that "the proof stays" would require moving the signature out of
 * the 256-locked codec. Reading the code closely, it does not: `colorForGeneration` and the
 * revealed-fog windows are pure colour arithmetic with no size dependency, and only `encode` and
 * `opaquePlaceholder` are locked. So this converter CALLS the existing signature rather than
 * copying or relocating it, and there is exactly one definition of a fog colour in the codebase.
 *
 * Deliberately pure: it takes a mask and returns an `IntArray`, with no `android.graphics` import,
 * so the whole rule is host-testable and the Android side is one `Bitmap.createBitmap` call at the
 * edge. The alpha convention is the codec's, restated because it is easy to invert: **mask alpha
 * zero means EXPLORED and becomes a fully transparent pixel; every non-zero mask value means fog
 * and becomes fully opaque.** The mask's own alpha magnitude is not a display opacity - the fog's
 * translucency comes from the overlay's display alpha, not from these pixels.
 */
object FogMaskArgb {

    /** Bytes one ARGB_8888 pixel occupies, for the budget arithmetic in [byteCost]. */
    const val BYTES_PER_PIXEL = 4

    /**
     * Row-major ARGB_8888 pixels, the layout `Bitmap.createBitmap(pixels, width, height, config)`
     * expects.
     */
    fun toArgb(mask: FogPixelMask, color: FogTileColor): IntArray {
        val pixels = IntArray(Math.multiplyExact(mask.width, mask.height))
        val opaque = (0xff shl 24) or
            (color.red shl 16) or
            (color.green shl 8) or
            color.blue
        var index = 0
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                pixels[index] = if (mask.alphaAt(x, y) == 0) TRANSPARENT else opaque
                index += 1
            }
        }
        return pixels
    }

    /** The signature-carrying form: what a published generation must be rasterised with. */
    fun toArgbForGeneration(mask: FogPixelMask, generation: Long): IntArray =
        toArgb(mask, FogTilePngCodec.colorForGeneration(generation))

    /**
     * What one bitmap of this size costs in memory, which is prototype A's central affordability
     * question and is arithmetic rather than a measurement.
     *
     * A `TileOverlay` holds the viewport as separate tiles the SDK can page; a single anchored
     * image is one allocation, and at the mosaic's own resolution that allocation is large. See
     * `FogMaskArgbTest` for the measured viewport's figure.
     */
    fun byteCost(width: Int, height: Int): Long =
        Math.multiplyExact(
            Math.multiplyExact(width.toLong(), height.toLong()),
            BYTES_PER_PIXEL.toLong(),
        )

    private const val TRANSPARENT = 0
}
