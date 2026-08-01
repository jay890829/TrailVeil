package app.trailveil.map

import java.net.URI

/**
 * The production renderer depends only on this replaceable style boundary.
 * It intentionally contains no account, billing identifier, API key, or user data.
 */
internal data class MapProviderConfiguration(
    val providerName: String,
    val styleUri: String,
) {
    init {
        require(providerName.isNotBlank()) { "providerName must not be blank" }
        val parsed = URI(styleUri)
        require(parsed.scheme == "https") { "the production style must use HTTPS" }
        require(!parsed.host.isNullOrBlank()) { "the production style must have a host" }
        require(parsed.userInfo == null) { "the production style must not contain credentials" }
        require(parsed.query == null) { "the production style must not contain a shared query key" }
        require(parsed.fragment == null) { "the production style must not contain a fragment" }
    }
}

internal val ProductionMapProvider = MapProviderConfiguration(
    providerName = "OpenFreeMap",
    styleUri = "https://tiles.openfreemap.org/styles/liberty",
)
