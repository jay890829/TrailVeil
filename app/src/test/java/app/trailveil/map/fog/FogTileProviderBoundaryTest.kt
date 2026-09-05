package app.trailveil.map.fog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogTileProviderBoundaryTest {
    @Test
    fun googlePocBridgeHasNoNetworkLoggingOrNoTileFallback() {
        val source = providerSource()

        assertFalse(source.contains("TileProvider.NO_TILE"))
        assertFalse(source.contains("com.google.android.gms:play-services-maps"))
        assertFalse(source.contains("Map Tiles API"))
        assertFalse(source.contains("HttpURLConnection"))
        assertFalse(source.contains("OkHttp"))
        assertFalse(source.contains("java.net.URL"))
        assertFalse(source.contains("android.util.Log"))
    }

    @Test
    fun overlayRefreshIsAnExplicitOncePerGenerationSeam() {
        val source = providerSource()
        val getTileBody = source.substringAfter("override fun getTile").substringBefore("\n}")

        assertFalse(getTileBody.contains("clearTileCache"))
        assertEquals(1, source.windowed("clearTileCache()".length).count { it == "clearTileCache()" })
        assertTrue(source.contains("fun refresh("))
        assertTrue(source.contains("onGenerationStarted(generation: Long)"))
        assertTrue(source.contains("fun onCanonicalPublished("))
        assertTrue(source.contains("FogTileRequestBarrier"))
        assertTrue(source.contains("map.snapshot"))
        assertTrue(source.contains("snapshotProvesCanonicalFog"))
        assertTrue(source.contains("snapshotProbeObservation"))
        assertTrue(source.contains("generationStartsNewPaletteCycle"))
        assertTrue(source.contains("rotateOverlay"))
        assertTrue(source.contains("current.remove()"))
    }

    @Test
    fun tileOverlayDisablesFadeAndRequestsMaximumOverlayZOrder() {
        val source = providerSource()

        assertTrue(source.contains(".fadeIn(false)"))
        assertTrue(source.contains(".transparency(0F)"))
        assertTrue(source.contains(".zIndex(Float.MAX_VALUE)"))
    }

    @Test
    fun overlayAttachAndDetachFailuresStayFailClosed() {
        val source = providerSource()

        assertTrue(source.contains("catch (_: Exception)"))
        assertTrue(source.contains("catch (_: LinkageError)"))
        assertTrue(source.contains("current?.remove()"))
        assertTrue(source.contains("overlay = null"))
    }

    @Test
    fun mapLoadedCallbackDoesNotDropTheOpaqueCoverBeforeCanonicalFog() {
        val source = activitySource()
        val callback = source.substringAfter("map.setOnMapLoadedCallback")
            .substringBefore("private fun installFogOverlay")

        assertFalse(callback.contains("fallback.visibility = View.GONE"))
        assertTrue(callback.contains("revealMapWhenCanonicalFogIsReady()"))
        assertTrue(source.contains("canonicalFogInstalledGeneration != null"))
        assertTrue(source.contains("scheduleFogInstallTimeout"))
        assertTrue(source.contains("FOG_INSTALL_TIMEOUT_MILLIS"))
        assertTrue(source.contains("canonicalFogPublishedGeneration != pending.id"))
    }

    @Test
    fun activityGuardsLateCallbacksAndStartsCanonicalSyncBeforeRendering() {
        val source = activitySource()

        assertTrue(source.contains("lifecycleGate.markDestroyed()"))
        assertTrue(source.contains("val lease = lifecycleGate.acquire() ?: return"))
        assertTrue(source.contains("if (!lifecycleGate.isCurrent(lease))"))
        assertTrue(source.contains("fogSyncJob?.cancel()"))
        assertTrue(source.contains("runtime.changeSynchronizer.synchronizeTo()"))
        assertTrue(source.contains("runtime.pointChanges.revisionsAfter(baseline.cursor)"))
        assertTrue(source.contains("requestCanonicalFog(map.cameraPosition, lease)"))
        assertTrue(source.contains("if (!lifecycleGate.enterTerminalFallback()) return"))
        assertTrue(source.contains("googleMap = null"))
        assertTrue(source.contains("catch (cancelled: CancellationException)"))
        assertTrue(source.contains("throw cancelled"))
    }

    private fun providerSource(): String {
        val repository = repositoryRoot()
        return File(
            repository,
            "app/src/google/java/app/trailveil/googlepoc/GoogleFogTileProvider.kt",
        ).readText()
    }

    private fun activitySource(): String = File(
        repositoryRoot(),
        "app/src/googlePoc/java/app/trailveil/googlepoc/GoogleMapsPocActivity.kt",
    ).readText()

    private fun repositoryRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(cwd, "settings.gradle.kts").isFile) cwd else requireNotNull(cwd.parentFile)
    }
}
