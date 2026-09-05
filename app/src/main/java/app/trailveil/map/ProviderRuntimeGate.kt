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

    /**
     * The provider granted its legacy renderer.
     *
     * `V02-005` stage 9 measured that renderer on six device images and never got a green run out
     * of it: out-of-memory inside the renderer, a null bitmap from the SDK, and timing failures. A
     * map that cannot draw is not a degraded map, it is an absent one, so this is terminal like the
     * others rather than something to retry. Recording, history and canonical fog are unaffected.
     */
    LEGACY_RENDERER,
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

/**
 * What the provider's renderer selection reported, as the gate needs to read it.
 *
 * `UNREPORTED` is not a failure. On the pinned Maps SDK the grant callback runs inline at process
 * start, so no composition ever observes this value in practice; the contract does not promise
 * that, and a provider that never reports is not thereby broken. Only an explicit legacy grant is
 * terminal.
 *
 * Known latent gap, recorded in the V02-008 evidence rather than closed: the Google surface
 * remembers its startup decision per composition, so an SDK that ever POSTED the callback could let
 * a first composition read `UNREPORTED`, build a legacy map, and keep it. Closing it means deriving
 * the decision from the grant as observable state instead of reading a value once.
 */
internal enum class ProviderRenderer {
    LEGACY,
    LATEST,
    UNREPORTED,
}

internal object ProviderRuntimeGate {
    fun startupDecision(
        keyConfigured: Boolean,
        keyReason: String,
        hasValidatedNetwork: Boolean,
        hasCompatibleServices: Boolean,
        initializeWithoutValidatedNetwork: Boolean = false,
        grantedRenderer: ProviderRenderer = ProviderRenderer.UNREPORTED,
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
        // Last, deliberately: a device with no key or no provider services has a more useful
        // answer than "the renderer is old", and the renderer is only knowable once the provider
        // has been asked at all.
        if (grantedRenderer == ProviderRenderer.LEGACY) {
            return ProviderStartupDecision(
                initializeMap = false,
                fallbackReason = ProviderFallbackReason.LEGACY_RENDERER,
            )
        }
        return ProviderStartupDecision(initializeMap = true, fallbackReason = null)
    }
}

/**
 * Whether this reason is the PROVIDER's, so that a build rendering with a different one could
 * plausibly draw a map on the same device.
 *
 * `V02-008` acceptance: every terminal reason on the Google variant must point the user at the
 * OpenFreeMap build. Every reason here is provider-specific except one - no validated internet
 * connection is the device's, and both providers fetch their tiles over it, so pointing a user
 * with no network at another build would be advice that cannot work. The `when` is exhaustive with
 * no `else` on purpose: a reason added later has to be classified deliberately rather than
 * inheriting a default that quietly promises a fix.
 *
 * Neutral by construction - no provider is named here, only the shape of the failure. The variant
 * that has somewhere to point supplies the copy.
 */
internal fun ProviderFallbackReason.isProviderSpecific(): Boolean = when (this) {
    ProviderFallbackReason.MISSING_KEY,
    ProviderFallbackReason.STRUCTURALLY_INVALID_KEY,
    ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE,
    ProviderFallbackReason.INITIALIZATION_FAILURE,
    ProviderFallbackReason.MAP_LOAD_TIMEOUT,
    ProviderFallbackReason.LEGACY_RENDERER,
    -> true

    ProviderFallbackReason.NO_VALIDATED_NETWORK -> false
}

internal fun ProviderFallbackReason.message(): String = when (this) {
    ProviderFallbackReason.MISSING_KEY -> "No external API key is configured."
    ProviderFallbackReason.STRUCTURALLY_INVALID_KEY -> "The external API key is invalid."
    ProviderFallbackReason.NO_VALIDATED_NETWORK -> "A validated internet connection is unavailable."
    ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE ->
        "Compatible provider services are unavailable."

    ProviderFallbackReason.INITIALIZATION_FAILURE -> "The map could not initialize."
    ProviderFallbackReason.MAP_LOAD_TIMEOUT -> "The map did not finish loading in time."
    ProviderFallbackReason.LEGACY_RENDERER ->
        "This device only offers the provider's legacy renderer."
}
