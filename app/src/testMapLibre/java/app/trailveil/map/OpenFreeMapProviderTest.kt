package app.trailveil.map

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which provider this build renders with, and on what terms.
 *
 * `V02-008`: compiled only into the unit tests of the build types that render with MapLibre, for
 * the same reason [ProductionMapProvider] itself is - the Google build must not carry a non-Google
 * map's identity, not even in a test fixture that would be compiled into its test APK.
 */
class OpenFreeMapProviderTest {
    @Test
    fun productionProviderIsNoKeyHttpsOpenFreeMapStyle() {
        val styleUri = requireNotNull(ProductionMapProvider.styleUri)
        val uri = URI(styleUri)

        assertEquals("OpenFreeMap", ProductionMapProvider.providerName)
        assertEquals("https", uri.scheme)
        assertEquals("tiles.openfreemap.org", uri.host)
        assertEquals("/styles/liberty", uri.path)
        assertNull(uri.userInfo)
        assertNull(uri.query)
        assertNull(uri.fragment)
    }
}
