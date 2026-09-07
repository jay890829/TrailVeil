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

    /**
     * The same rule at a chosen output size, sampled nearest-neighbour.

     * `V03-011` arm 2 rasterises the mosaic once per generation and anchors it to the ground, so
     * its resolution is a free choice rather than the mask's. Composing at the tiles' own
     * resolution would be 3840x4096 = 62.9 MB for the measured 15x16 completion, against 10.4 MB at
     * a 1080x2400 viewport - and the deciding argument is not the memory but the SAMPLING. A
     * 3840x4096 image over a 1080x2400 readback is a six-fold oversample the SDK discards
     * resampling down, and at a tilted pose a ground-anchored image cannot match a perspective
     * projection's varying pixel density at more than one depth, so the extra pixels do not help
     * the far plane either. The other provider already ships this choice: MapLibre installs its fog
     * as an `ImageSource` with `rasterResampling(NEAREST)` at 768x768.

     * Nearest-neighbour on purpose, and it is the SAME decision `rasterResampling(NEAREST)` makes.
     * Fog has exactly two states per pixel, explored and not; interpolating between them would
     * invent partially-explored ground that no mask contains, and would put pixels on screen that
     * `FogTilePngCodec.matchesRevealedFog` cannot recognise - which is the whole proof.

     * Integer stepping rather than floating accumulation so a row's samples cannot drift: each
     * output coordinate maps to a source coordinate by one multiply and one divide, which is exact
     * and identical for every row.
     */
    fun toArgbScaled(
        mask: FogPixelMask,
        color: FogTileColor,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        require(targetWidth > 0 && targetHeight > 0) {
            "target size must be positive, was ${targetWidth}x$targetHeight"
        }
        if (targetWidth == mask.width && targetHeight == mask.height) return toArgb(mask, color)

        val pixels = IntArray(Math.multiplyExact(targetWidth, targetHeight))
        val opaque = (0xff shl 24) or
            (color.red shl 16) or
            (color.green shl 8) or
            color.blue
        // Sample the CENTRE of each output pixel, so a 2x downsample takes the same source column
        // a 2x upsample would put it back into. Sampling the corner biases every output image
        // towards the mask's top-left by half an output pixel, which at a tilted pose is ground.
        val sourceX = IntArray(targetWidth) { x ->
            ((2 * x + 1).toLong() * mask.width / (2L * targetWidth)).toInt()
                .coerceIn(0, mask.width - 1)
        }
        var index = 0
        for (y in 0 until targetHeight) {
            val sy = ((2 * y + 1).toLong() * mask.height / (2L * targetHeight)).toInt()
                .coerceIn(0, mask.height - 1)
            for (x in 0 until targetWidth) {
                pixels[index] = if (mask.alphaAt(sourceX[x], sy) == 0) TRANSPARENT else opaque
                index += 1
            }
        }
        return pixels
    }

    /** The signature-carrying form at a chosen output size. */
    fun toArgbScaledForGeneration(
        mask: FogPixelMask,
        generation: Long,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray = toArgbScaled(
        mask = mask,
        color = FogTilePngCodec.colorForGeneration(generation),
        targetWidth = targetWidth,
        targetHeight = targetHeight,
    )

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
