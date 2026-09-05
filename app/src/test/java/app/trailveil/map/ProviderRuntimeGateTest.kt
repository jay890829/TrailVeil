package app.trailveil.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRuntimeGateTest {
    @Test
    fun missingKeyWinsBeforeNetworkOrProviderChecks() {
        val decision = ProviderRuntimeGate.startupDecision(
            keyConfigured = false,
            keyReason = "MISSING_KEY",
            hasValidatedNetwork = false,
            hasCompatibleServices = false,
        )

        assertFalse(decision.initializeMap)
        assertEquals(ProviderFallbackReason.MISSING_KEY, decision.fallbackReason)
    }

    @Test
    fun rejectedKeyIsFailClosedWithoutInitializingTheProvider() {
        val decision = ProviderRuntimeGate.startupDecision(
            keyConfigured = false,
            keyReason = "CONFIG_FILE_INSIDE_REPOSITORY",
            hasValidatedNetwork = true,
            hasCompatibleServices = true,
        )

        assertFalse(decision.initializeMap)
        assertEquals(ProviderFallbackReason.STRUCTURALLY_INVALID_KEY, decision.fallbackReason)
    }

    @Test
    fun defaultPolicyRequiresValidatedNetworkAndProviderServicesBeforeInitialization() {
        val noNetwork = ProviderRuntimeGate.startupDecision(
            keyConfigured = true,
            keyReason = "VALID",
            hasValidatedNetwork = false,
            hasCompatibleServices = true,
        )
        val noServices = ProviderRuntimeGate.startupDecision(
            keyConfigured = true,
            keyReason = "VALID",
            hasValidatedNetwork = true,
            hasCompatibleServices = false,
        )

        assertEquals(ProviderFallbackReason.NO_VALIDATED_NETWORK, noNetwork.fallbackReason)
        assertEquals(ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE, noServices.fallbackReason)
    }

    @Test
    fun productionPolicyMayInitializeOfflineButStillRequiresProviderServices() {
        val offlineWithServices = ProviderRuntimeGate.startupDecision(
            keyConfigured = true,
            keyReason = "VALID",
            hasValidatedNetwork = false,
            hasCompatibleServices = true,
            initializeWithoutValidatedNetwork = true,
        )
        val offlineWithoutServices = ProviderRuntimeGate.startupDecision(
            keyConfigured = true,
            keyReason = "VALID",
            hasValidatedNetwork = false,
            hasCompatibleServices = false,
            initializeWithoutValidatedNetwork = true,
        )

        assertTrue(offlineWithServices.initializeMap)
        assertNull(offlineWithServices.fallbackReason)
        assertFalse(offlineWithoutServices.initializeMap)
        assertEquals(
            ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE,
            offlineWithoutServices.fallbackReason,
        )
    }

    @Test
    fun initializedDecisionHasNoFallbackReason() {
        val decision = ProviderRuntimeGate.startupDecision(
            keyConfigured = true,
            keyReason = "VALID",
            hasValidatedNetwork = true,
            hasCompatibleServices = true,
        )

        assertTrue(decision.initializeMap)
        assertNull(decision.fallbackReason)
    }

    @Test
    fun keyShapeAcceptsOnlyTheExpectedLengthAndPrefix() {
        val valid = "AIza" + "a".repeat(35)

        assertTrue(ProviderKeyShape.isStructurallyValid(valid))
        assertFalse(ProviderKeyShape.isStructurallyValid("AIza" + "a".repeat(34)))
        assertFalse(ProviderKeyShape.isStructurallyValid("not-a-provider-key"))
        assertTrue(ProviderKeyShape.isStructurallyValid("  $valid  "))
    }

    @Test
    fun postStartupFailuresHaveLocalFallbackMessages() {
        assertEquals(
            "The map could not initialize.",
            ProviderFallbackReason.INITIALIZATION_FAILURE.message(),
        )
        assertEquals(
            "The map did not finish loading in time.",
            ProviderFallbackReason.MAP_LOAD_TIMEOUT.message(),
        )
    }

    @Test
    fun aLegacyRendererGrantIsTerminalAndNamesItself() {
        val legacy = ProviderRuntimeGate.startupDecision(
            keyConfigured = true,
            keyReason = "VALID",
            hasValidatedNetwork = true,
            hasCompatibleServices = true,
            grantedRenderer = ProviderRenderer.LEGACY,
        )

        assertFalse(legacy.initializeMap)
        assertEquals(ProviderFallbackReason.LEGACY_RENDERER, legacy.fallbackReason)
    }

    @Test
    fun anUnreportedRendererIsNotAFailure() {
        // The grant arrives on the main looper and may not have landed on the first composition.
        // A provider that has not answered yet is not a provider that answered badly.
        listOf(ProviderRenderer.UNREPORTED, ProviderRenderer.LATEST).forEach { renderer ->
            val decision = ProviderRuntimeGate.startupDecision(
                keyConfigured = true,
                keyReason = "VALID",
                hasValidatedNetwork = true,
                hasCompatibleServices = true,
                grantedRenderer = renderer,
            )

            assertTrue("$renderer must initialize the map", decision.initializeMap)
            assertNull(decision.fallbackReason)
        }
    }

    @Test
    fun aMissingKeyOutranksALegacyRendererGrant() {
        // A device with no key has a more useful answer than "the renderer is old", and the
        // renderer is only knowable once the provider has been asked at all.
        val decision = ProviderRuntimeGate.startupDecision(
            keyConfigured = false,
            keyReason = "MISSING_KEY",
            hasValidatedNetwork = true,
            hasCompatibleServices = true,
            grantedRenderer = ProviderRenderer.LEGACY,
        )

        assertEquals(ProviderFallbackReason.MISSING_KEY, decision.fallbackReason)
    }

    @Test
    fun everyFallbackReasonHasALocalMessage() {
        ProviderFallbackReason.entries.forEach { reason ->
            assertTrue("$reason has no message", reason.message().isNotBlank())
        }
    }

    /**
     * `V02-008` acceptance: every terminal reason the Google variant can reach points the user at
     * the OpenFreeMap build. The surface renders that pointer for exactly the provider-specific
     * reasons, so the criterion is this list - stated by name rather than by iterating the enum,
     * which would agree with whatever the code said.
     */
    @Test
    fun everyReasonTheGoogleVariantCanReachIsTheProvidersOwn() {
        listOf(
            ProviderFallbackReason.MISSING_KEY,
            ProviderFallbackReason.STRUCTURALLY_INVALID_KEY,
            ProviderFallbackReason.PROVIDER_SERVICES_UNAVAILABLE,
            ProviderFallbackReason.INITIALIZATION_FAILURE,
            ProviderFallbackReason.MAP_LOAD_TIMEOUT,
            ProviderFallbackReason.LEGACY_RENDERER,
        ).forEach { reason ->
            assertTrue(
                "$reason must point at the other provider's build",
                reason.isProviderSpecific(),
            )
        }
    }

    /**
     * The one reason that must NOT point elsewhere: both providers fetch tiles over the same
     * connection, so telling a user with no network to install the other build is advice that
     * cannot work. It is unreachable on the Google variant today - that variant initializes
     * without a validated network - and this pins the classification, not the reachability.
     */
    @Test
    fun aMissingNetworkIsTheDevicesProblemAndNotTheProvidersOwn() {
        assertFalse(ProviderFallbackReason.NO_VALIDATED_NETWORK.isProviderSpecific())
    }
}
