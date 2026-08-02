package app.trailveil.map

import app.trailveil.data.map.ViewportBounds
import app.trailveil.map.fog.FogPixelMask
import app.trailveil.map.fog.FogTileBounds
import app.trailveil.map.fog.FogTileMosaic
import app.trailveil.map.fog.FogViewportRender
import app.trailveil.map.fog.FogViewportRequest
import app.trailveil.map.fog.GeoPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FogCanonicalRetryTest {
    @Test
    fun transientRenderAndInstallFailuresRetryUntilCanonicalFrameSucceeds() = runTest {
        val request = FogViewportRequest(
            center = GeoPoint(latitude = 25.0330, longitude = 121.5654),
            mapZoom = 14.0,
        )
        val expected = FogViewportRender(
            request = request,
            keys = emptyList(),
            queryBounds = ViewportBounds(
                south = 25.0,
                north = 25.1,
                west = 121.5,
                east = 121.6,
            ),
            mosaic = FogTileMosaic(
                mask = FogPixelMask(1, 1, byteArrayOf(184.toByte())),
                bounds = FogTileBounds(
                    westLongitude = 121.5,
                    southLatitude = 25.0,
                    eastLongitude = 121.6,
                    northLatitude = 25.1,
                ),
                tileCount = 1,
            ),
        )
        var renderAttempts = 0
        var installAttempts = 0
        val failures = mutableListOf<String?>()

        val actual = renderCanonicalFogWithRetry(
            request = request,
            retryDelayMillis = 1_000L,
            render = {
                renderAttempts += 1
                if (renderAttempts == 1) error("transient Room read")
                expected
            },
            installAndAwait = {
                installAttempts += 1
                if (installAttempts == 1) error("transient style install")
            },
            onFailure = { failure -> failures += failure.message },
        )

        assertSame(expected, actual)
        assertEquals(3, renderAttempts)
        assertEquals(2, installAttempts)
        assertEquals(
            listOf("transient Room read", "transient style install"),
            failures,
        )
    }
}
