package app.trailveil.map

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.MapView
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** View-buffer controls: no Maps renderer, API key, Room fixture or visual-status tag oracle. */
class GoogleFogCoverCommitTest {
    @After fun reset() { GoogleMapSurfaceTestHooks.reset() }

    @Test fun commitAcknowledgesTheDrawnCoverBuffer() = runBlocking {
        val view = AtomicReference<MapView>()
        GoogleMapSurfaceTestHooks.content.set {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                MapView(context).also { it.setBackgroundColor(Color.WHITE); view.set(it) }
            })
        }
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            withContext(Dispatchers.Main) {
                val mapView = checkNotNull(view.get())
                assertTrue(mapView.isHardwareAccelerated)
                assertTrue(mapView.width > 0 && mapView.height > 0)
                val cover = GoogleFogSafetyOverlay(mapView)
                try {
                    cover.setVisible(true)
                    assertTrue(withTimeout(3_000) { cover.awaitCommitted() })
                    val bitmap = createBitmap(mapView.rootView.width, mapView.rootView.height, Bitmap.Config.ARGB_8888)
                    try {
                        val activity = AtomicReference<GoogleMapSurfaceTestActivity>()
                        scenario.onActivity { activity.set(it) }
                        val result = withTimeout(3_000) {
                            suspendCancellableCoroutine<Int> { continuation ->
                                PixelCopy.request(checkNotNull(activity.get()).window, bitmap,
                                    { value -> if (continuation.isActive) continuation.resume(value) }, Handler(Looper.getMainLooper()))
                            }
                        }
                        assertEquals(PixelCopy.SUCCESS, result)
                        val location = IntArray(2)
                        mapView.getLocationInWindow(location)
                        val actual = bitmap[location[0] + mapView.width / 2, location[1] + mapView.height / 2]
                        // Independent 184/255 coat of #1F262B over white, not opaque or bare.
                        assertTrue("red=${Color.red(actual)}", Color.red(actual) in 92..95)
                        assertTrue("green=${Color.green(actual)}", Color.green(actual) in 97..100)
                        assertTrue("blue=${Color.blue(actual)}", Color.blue(actual) in 101..104)
                    } finally { bitmap.recycle() }
                } finally { cover.release() }
            }
        }
    }

    @Test fun detachedCoverNeverAcknowledgesAFrame() = runBlocking {
        withContext(Dispatchers.Main) {
            val cover = GoogleFogSafetyOverlay(MapView(InstrumentationRegistry.getInstrumentation().targetContext))
            try {
                cover.setVisible(true)
                assertFalse(withTimeout(500) { cover.awaitCommitted() })
            } finally { cover.release() }
        }
    }

    @Test fun releaseBeforeDrawInvalidatesThePendingAcknowledgement() = runBlocking {
        val view = AtomicReference<MapView>()
        GoogleMapSurfaceTestHooks.content.set {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context -> MapView(context).also(view::set) })
        }
        ActivityScenario.launch(GoogleMapSurfaceTestActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            withContext(Dispatchers.Main.immediate) {
                val cover = GoogleFogSafetyOverlay(checkNotNull(view.get()))
                cover.setVisible(true)
                val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    withTimeout(3_000) { cover.awaitCommitted() }
                }
                cover.release()
                assertFalse(pending.await())
            }
        }
    }
}
