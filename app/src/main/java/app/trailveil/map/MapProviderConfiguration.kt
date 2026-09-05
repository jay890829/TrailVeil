package app.trailveil.map

import java.net.URI

/**
 * The production renderer depends only on this replaceable style boundary.
 * It intentionally contains no account, billing identifier, API key, or user data.
 *
 * `V02-008`: [styleUri] is nullable because only one of the two providers is style-driven. A
 * provider whose SDK fetches its own basemap has no style URI to point at, and inventing one would
 * put a URL in the shipped binary that nothing ever requests. Null says that; the validation below
 * still applies in full to any provider that does supply one.
 */
internal data class MapProviderConfiguration(
    val providerName: String,
    val styleUri: String?,
) {
    init {
        require(providerName.isNotBlank()) { "providerName must not be blank" }
        if (styleUri != null) {
            val parsed = URI(styleUri)
            require(parsed.scheme == "https") { "the production style must use HTTPS" }
            require(!parsed.host.isNullOrBlank()) { "the production style must have a host" }
            require(parsed.userInfo == null) { "the production style must not contain credentials" }
            require(parsed.query == null) { "the production style must not contain a shared query key" }
            require(parsed.fragment == null) { "the production style must not contain a fragment" }
        }
    }
}
