package app.trailveil.googlepoc

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.MapsInitializer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** `V02-005` stage 3: requested-vs-granted renderer record — the renderer-pin datum. */
data class GoogleRendererPinResult(
    val requested: String,
    /** LEGACY | LATEST | UNREPORTED | UNAVAILABLE_API */
    val granted: String,
) {
    /** A legacy request answered with anything but LEGACY collapses the renderer matrix. */
    val collapsed: Boolean = requested == "legacy" && granted != "LEGACY"

    fun asEvidenceTokens(): String =
        "rendererRequested=$requested rendererGranted=$granted rendererCollapsed=$collapsed"
}

/**
 * MapsInitializer's renderer preference is process-wide and first-call-latched, so every spike
 * invocation calls this exactly once, BEFORE launching any Activity, in its own instrumentation
 * process. The initialize call runs on the main thread via runOnMainSync (the callback is
 * delivered on the main looper — awaiting it there would deadlock); the await happens on the
 * instrumentation thread with a 30 s bound (renderer cold load on a cold-booted image is slow).
 */
object GoogleRendererPin {
    fun initialize(context: Context, requested: String, timeoutSeconds: Long = 30L): GoogleRendererPinResult {
        val preference = when (requested) {
            "legacy" -> MapsInitializer.Renderer.LEGACY
            "latest" -> MapsInitializer.Renderer.LATEST
            else -> error("unknown renderer request '$requested'")
        }
        val granted = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var unavailable = false
        instrumentation.runOnMainSync {
            try {
                MapsInitializer.initialize(context.applicationContext, preference) { renderer ->
                    granted.set(renderer.name)
                    latch.countDown()
                }
            } catch (_: Exception) {
                unavailable = true
                latch.countDown()
            } catch (_: LinkageError) {
                unavailable = true
                latch.countDown()
            }
        }
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        return GoogleRendererPinResult(
            requested = requested,
            granted = when {
                unavailable -> "UNAVAILABLE_API"
                else -> granted.get() ?: "UNREPORTED"
            },
        )
    }
}
