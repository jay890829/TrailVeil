package app.trailveil.map.fog

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Timing-only codec probe with Android-decoded pixel equivalence outside the clocks. */
class PngEncodingCostTest {
    @Test fun encodeAndDecodeCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("y10PngCost") == "true")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val appHash = hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes())
        check(appHash == args.getString("y10ExpectedApkHash"))
        val testHash = hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())
        val patterns = (args.getString("y10PatternOrder") ?: "opaque,clear,sparse,noise,ownedOpaque,ownedClear,ownedSparse").split(',')
        check(patterns.sorted() == listOf("clear", "noise", "opaque", "ownedClear", "ownedOpaque", "ownedSparse", "sparse"))
        for (pattern in patterns) {
            val alpha = ByteArray(256 * 256) { i ->
                when (pattern) {
                    "opaque" -> 184.toByte()
                    "clear" -> 0
                    "sparse" -> if ((i % 256) % 32 < 2 || (i / 256) % 32 < 2) 0 else 184.toByte()
                    else -> if (((i * 1103515245 + 12345) ushr 16) and 1 == 0) 0 else 184.toByte()
                }
            }
            val key = FogTileKey(14, 8192, 8192, FogRenderVersions.CURRENT)
            val mask = when (pattern) {
                "ownedOpaque" -> FogTileRenderer().render(key, emptyList())
                "ownedClear" -> FogTileRenderer(FogRenderStyle(fogAlpha = 0)).render(key, emptyList())
                "ownedSparse" -> FogTileRenderer().render(key, listOf(TrackSegment(0, listOf(GeoPoint(
                    WebMercator.latitudeAtNormalizedY(8192.5 / 16384), 8192.5 / 16384 * 360 - 180)))))
                else -> FogPixelMask(256, 256, alpha)
            }
            val expectedAlpha = mask.copyAlpha()
            val inputHash = hash(expectedAlpha)
            repeat(7) { sample ->
                val colour = FogTilePngCodec.colorForGeneration(sample + 1L)
                var png = ByteArray(0)
                val encodeCpu = Debug.threadCpuTimeNanos(); val encodeStart = System.nanoTime()
                repeat(16) { png = FogTilePngCodec.encode(mask, colour) }
                val encodeWall = System.nanoTime() - encodeStart
                val encodeCpuElapsed = Debug.threadCpuTimeNanos() - encodeCpu
                val decodeCpu = Debug.threadCpuTimeNanos(); val decodeStart = System.nanoTime()
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
                val decodeWall = System.nanoTime() - decodeStart
                val decodeCpuElapsed = Debug.threadCpuTimeNanos() - decodeCpu
                val pixels = IntArray(256 * 256)
                try {
                    check(bitmap.width == 256 && bitmap.height == 256)
                    bitmap.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                } finally { bitmap.recycle() }
                val opaque = (0xff shl 24) or (colour.red shl 16) or (colour.green shl 8) or colour.blue
                for (i in pixels.indices) check(pixels[i] == if (expectedAlpha[i] == 0.toByte()) 0 else opaque) {
                    "decoded pixel mismatch: $pattern sample=$sample pixel=$i"
                }
                val buffer = ByteBuffer.allocate(pixels.size * 4)
                for (pixel in pixels) buffer.putInt(pixel)
                val row = JSONObject().put("pattern", pattern).put("sample", sample).put("repeats", 16)
                    .put("encodeWallNs", encodeWall / 16.0).put("encodeCpuNs", encodeCpuElapsed / 16.0)
                    .put("decodeWallNs", decodeWall).put("decodeCpuNs", decodeCpuElapsed).put("pngBytes", png.size)
                    .put("bitDepth", png[24].toInt() and 255).put("colorType", png[25].toInt() and 255)
                    .put("inputSha256", inputHash).put("decodedSha256", hash(buffer.array()))
                    .put("appSha256", appHash).put("testSha256", testHash).put("fingerprint", Build.FINGERPRINT)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Y10_PNG_COST $row\n") })
            }
        }
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
