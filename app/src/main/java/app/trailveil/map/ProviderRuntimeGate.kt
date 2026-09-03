package app.trailveil.map

/** Provider-neutral key-shape and startup decisions shared by an isolated provider PoC. */
internal object ProviderKeyShape {
    private val keyPattern = Regex("^AIza[A-Za-z0-9_-]{35}$")

    fun isStructurallyValid(value: String?): Boolean =
        value?.trim()?.let(keyPattern::matches) == true
}

internal enum class ProviderFallbackReason {
    MISSING_KEY,
    STRUCTURALLY_INVALID_KEY,
    NO_VALIDATED_NETWORK,
    PROVIDER_SERVICES_UNAVAILABLE,
    INITIALIZATION_FAILURE,
    MAP_LOAD_TIMEOUT,
}

internal data class ProviderStartupDecision(
    val initializeMap: Boolean,
    val fallbackReason: ProviderFallbackReason?,
) {
    init {
        require(initializeMap == (fallbackReason == null)) {
            "an initialized map cannot have a fallback reason"
        }
    }
}

internal object ProviderRuntimeGate {
    fun startupDecision(
        keyConfigured: Boolean,
        keyReason: String,
        hasValidatedNetwork: Boolean,
        hasCompatibleServices: Boolean,
        initializeWithoutValidatedNetwork: Boolean = false,
    ): ProviderStartupDecision {
        if (!keyConfigured) {
            val reason = if (keyReason == "MISSING_KEY") {
                ProviderFallbackReason.MISSING_KEY
            } else {
                ProviderFallbackReason.STRUCTURALLY_INVALID_KEY
            }
            return ProviderStartupDecision(initializeMap = false, fallbackReason = reason)
        }
        if (!hasValidatedNetwork && !initializeWithoutValidatedNetwork) {
            return ProviderStartupDecision(
                initializeMap = false,
                fallbackReason = ProviderFallbackReason.NO_VALIDATED_NETWORK,
            )
        }
        if (!hasCompatibleServices) {
            return ProviderStartupDecision(
                initializeMap = false,
                fallbackReason = ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE,
            )
        }
        return ProviderStartupDecision(initializeMap = true, fallbackReason = null)
    }
}

internal fun ProviderFallbackReason.message(): String = when (this) {
    ProviderFallbackReason.MISSING_KEY -> "No external API key is configured."
    ProviderFallbackReason.STRUCTURALLY_INVALID_KEY -> "The external API key is invalid."
    ProviderFallbackReason.NO_VALIDATED_NETWORK -> "A validated internet connection is unavailable."
    ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE ->
        "Compatible provider services are unavailable."

    ProviderFallbackReason.INITIALIZATION_FAILURE -> "The map could not initialize."
    ProviderFallbackReason.MAP_LOAD_TIMEOUT -> "The map did not finish loading in time."
}
