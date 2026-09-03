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
}
