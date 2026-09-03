package app.trailveil.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hosted-CI tripwires for the Stage-7 Google overlay/follow seam. */
class GoogleMapSurfaceStage7SourceTest {
    @Test
    fun googleActualForwardsTheNeutralInteractiveInputsToTheHostedSurface() {
        val actual = googleSource("TrailVeilMapSurface.kt")
        val hosted = googleSource("GoogleHostedMapSurface.kt")
        val gestureView = googleSource("GestureOwningGoogleMapView.kt")
        listOf(
            "cameraRequest = cameraRequest",
            "currentLocation = currentLocation",
            "followLocation = followLocation",
            "trackOverlay = trackOverlay",
        ).forEach { forwarded ->
            assertTrue("missing hosted input: $forwarded", actual.contains(forwarded))
        }
        listOf(
            "if (cameraRequest?.point == target)",
            "request.zoom == null",
            "beginProgrammedFlight()",
            "beginFollowEase()",
            "endProgrammedFlight(ticket)",
            "endFollowEase(ticket)",
            "FOLLOW_EASE_MILLIS",
            "followCameraMove(",
        ).forEach { contract ->
            assertTrue("missing Stage-7 camera contract: $contract", hosted.contains(contract) || actual.contains(contract))
        }
        assertTrue(gestureView.contains("requestDisallowInterceptTouchEvent(true)"))
        assertTrue(
            "Stage 8 detail camera must use the SDK bounds update",
            hosted.contains("CameraUpdateFactory.newLatLngBounds("),
        )
        assertTrue(
            "Stage 8 detail bounds-fit must wait for a measured MapView",
            hosted.contains("mapView.width <= 0 || mapView.height <= 0") &&
                hosted.contains("mapView.isLaidOut"),
        )
        assertTrue(
            "Stage 8 must key detail framing by the persisted track request",
            hosted.contains("overlay.requestId") &&
                hosted.contains("detailFitRequestId") &&
                hosted.contains("detailFitEpoch"),
        )
        assertTrue(
            "Stage 8 must treat duplicate points as one effective singleton",
            hosted.contains("distinctBy"),
        )
        assertTrue(hosted.contains("DETAIL_SINGLE_POINT_ZOOM = 16.0f"))
        assertTrue(hosted.contains("DETAIL_BOUNDS_PADDING_PX = 72"))
    }

    @Test
    fun overlaysAreProofGatedAndZonesUseTheUnprovableHideReplanPath() {
        val overlays = googleSource("GoogleMapOverlays.kt")
        val visibilityGate = moduleSource("FogOverlayVisibilityGate.kt")
        val binding = googleSource("GoogleMapSurfaceBinding.kt")
        val fogBinding = googleSource("GoogleCanonicalFogSurfaceBinding.kt")
        val prover = googleSource("GoogleFogSnapshotProver.kt")
        val hosted = googleSource("GoogleHostedMapSurface.kt")
        listOf(
            "fun hideUntilProof(",
            "fun revealForGeneration(generation: Long)",
            "visibleGeneration",
            "highestProvenGeneration",
            "setVisibility(false)",
            "setVisibility(true)",
            "zIndex(Float.MAX_VALUE)",
            "TRACK_LINE_ALPHA = 0.9f",
            "WORLD_TILE_SIZE_PX",
            "datelineSafePaths",
            "exclusionZonesForProof",
        ).forEach { contract ->
            assertTrue(
                "missing overlay contract: $contract",
                overlays.contains(contract) || binding.contains(contract) || visibilityGate.contains(contract),
            )
        }
        listOf(
            "exclusionZonesForProof = newBinding::exclusionZonesForProof",
            "onUnprovableProofPlan = newBinding::hideOverlaysUntilProof",
            "prepareFogProofPlan(plan, onUnprovablePlan)",
            "onProofAccepted",
            "onOverlayDataChanged",
            "reconcileFogOverlayCoordinatorState(",
            "pendingGeneration = state.pendingGeneration",
            "coverUp = state.coverUp",
            "retryScheduled = state.retryScheduled",
            "terminal = state.terminal",
        ).forEach { contract ->
            assertTrue(
                "missing proof-gating contract: $contract",
                fogBinding.contains(contract) || prover.contains(contract) || hosted.contains(contract),
            )
        }
    }

    @Test
    fun proofPlanPolicyCannotTurnZoneBlockedTilesIntoAPass() {
        val policy = moduleSource("FogSnapshotProofOverlayPolicy.kt")
        assertTrue(policy.contains("if (plan.isProvable())"))
        assertTrue(policy.contains("val hidden = hideOverlays()"))
        assertTrue(policy.contains("canProve = false"))
    }

    private fun googleSource(name: String): String = source("src/googlePoc/java/app/trailveil/map/$name")

    private fun moduleSource(name: String): String = source("src/main/java/app/trailveil/map/fog/$name")

    private fun source(relativePath: String): String = {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val module = if (File(root, "settings.gradle.kts").isFile) File(root, "app") else root
        module.resolve(relativePath).readText()
    }()
}
