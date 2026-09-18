package app.trailveil.map

import android.graphics.Bitmap
import android.os.Looper
import app.trailveil.map.fog.FogTileMosaic
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An optional observer, never a shared payload slot. Instrumentation restores it in finally. */
internal object RasterFogPreparationTestProbe {
    @Volatile var onChunk: ((FogTileMosaic, Int, Job?) -> Unit)? = null
    @Volatile var onPlaceholderStart: ((Job?) -> Unit)? = null
}

/** Each retry owns one immutable-after-construction pixel snapshot for exactly one mosaic. */
internal class PreparedRasterFog private constructor(
    val mosaic: FogTileMosaic,
    private val pixels: IntArray,
    private val preparationThread: Thread,
) {
    fun createBitmapFor(actual: FogTileMosaic): Bitmap {
        check(actual === mosaic) { "raster payload belongs to a different mosaic" }
        check(Looper.myLooper() == Looper.getMainLooper()) { "raster Bitmap installation must run on Main" }
        check(Thread.currentThread() !== preparationThread) { "raster pixels must be prepared off Main" }
        return Bitmap.createBitmap(pixels, mosaic.mask.width, mosaic.mask.height, Bitmap.Config.ARGB_8888)
    }

    companion object {
        internal suspend fun prepare(mosaic: FogTileMosaic): PreparedRasterFog {
            val context = currentCoroutineContext()
            context.ensureActive()
            val mask = mosaic.mask
            require(mask.width > 0 && mask.height > 0) { "raster dimensions must be positive" }
            val pixels = IntArray(Math.multiplyExact(mask.width, mask.height))
            val rgb = MAPLIBRE_FOG_COLOR and 0x00ffffff
            val lookup = IntArray(256) { alpha -> if (alpha == 0) 0 else (alpha shl 24) or rgb }
            val observer = RasterFogPreparationTestProbe.onChunk
            var start = 0
            while (start < pixels.size) {
                context.ensureActive()
                val end = start + minOf(RASTER_PACK_CHUNK_PIXELS, pixels.size - start)
                mask.packArgbLookupInto(pixels, start, end, lookup)
                observer?.invoke(mosaic, end, context[Job])
                start = end
            }
            context.ensureActive()
            return PreparedRasterFog(mosaic, pixels, Thread.currentThread())
        }
    }
}

internal suspend fun prepareRasterFog(mosaic: FogTileMosaic): PreparedRasterFog = PreparedRasterFog.prepare(mosaic)

internal const val RASTER_PACK_CHUNK_PIXELS = 4096
