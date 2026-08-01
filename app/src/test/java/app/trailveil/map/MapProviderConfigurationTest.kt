package app.trailveil.map

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MapProviderConfigurationTest {
    @Test
    fun productionProviderIsNoKeyHttpsOpenFreeMapStyle() {
        val uri = URI(ProductionMapProvider.styleUri)

        assertEquals("OpenFreeMap", ProductionMapProvider.providerName)
        assertEquals("https", uri.scheme)
        assertEquals("tiles.openfreemap.org", uri.host)
        assertEquals("/styles/liberty", uri.path)
        assertNull(uri.userInfo)
        assertNull(uri.query)
        assertNull(uri.fragment)
    }

    @Test
    fun rejectsNonHttpsStyle() {
        assertThrows(IllegalArgumentException::class.java) {
            MapProviderConfiguration(
                providerName = "test",
                styleUri = "http://tiles.example.test/style",
            )
        }
    }

    @Test
    fun rejectsCredentialsOrSharedQueryKeys() {
        val credentialStyleUri = URI(
            "https",
            "user:secret",
            "tiles.example.test",
            -1,
            "/style",
            null,
            null,
        ).toString()
        val sharedQueryKey = listOf("api", "key").joinToString("_") + "=shared"
        val queryStyleUri = URI(
            "https",
            null,
            "tiles.example.test",
            -1,
            "/style",
            sharedQueryKey,
            null,
        ).toString()
        assertThrows(IllegalArgumentException::class.java) {
            MapProviderConfiguration(
                providerName = "test",
                styleUri = credentialStyleUri,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapProviderConfiguration(
                providerName = "test",
                styleUri = queryStyleUri,
            )
        }
    }
}
