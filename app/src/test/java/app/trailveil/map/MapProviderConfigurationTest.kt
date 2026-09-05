package app.trailveil.map

import java.net.URI
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The neutral half of the style boundary: what any provider's configuration must satisfy.
 *
 * `V02-008`: the assertions about WHICH provider this build uses moved to
 * `src/testMapLibre`, which is compiled only into the variants that render with it. This file is
 * shared, so anything asserted here has to be true of every provider.
 */
class MapProviderConfigurationTest {
    @Test
    fun acceptsAProviderThatSuppliesNoStyleDocument() {
        val configuration = MapProviderConfiguration(providerName = "test", styleUri = null)

        assertThrows(IllegalArgumentException::class.java) {
            MapProviderConfiguration(providerName = " ", styleUri = null)
        }
        assert(configuration.styleUri == null)
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
