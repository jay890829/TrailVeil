package app.trailveil.map

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keyless hosted-CI tripwires for the stage-5 provider binding. */
class GoogleMapSurfaceStage5SourceTest {
    @Test
    fun hostedSurfaceOwnsLifecycleSavedStateAndAccessibilityBoundary() {
        val hosted = googleSource("GoogleHostedMapSurface.kt")
        val actual = googleSource("TrailVeilMapSurface.kt")
        val mapLibre = mapLibreSource()

        assertTrue(hosted.contains("GestureOwningGoogleMapView"))
        assertTrue(hosted.contains("GoogleMapViewLifecycleBinding"))
        assertTrue(hosted.contains("registerSavedStateProvider"))
        assertTrue(hosted.contains("MAP_SAVED_STATE_PROVIDER_KEY"))
        assertTrue(hosted.contains("MAP_SAVED_STATE_PAYLOAD_KEY"))
        assertTrue(hosted.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertTrue(mapLibre.contains("MAP_SAVED_STATE_PROVIDER_KEY"))
        assertTrue(mapLibre.contains("MAP_SAVED_STATE_PAYLOAD_KEY"))
        assertTrue(actual.contains("hasValidatedInternet(context)"))
        assertTrue(actual.contains("isGooglePlayServicesAvailable(context)"))
        assertTrue(actual.contains("initializeWithoutValidatedNetwork = true"))
    }

    @Test
    fun sdkBindingPinsHardeningAndRegistersNoPoiOrMapClickSurface() {
        val binding = googleSource("GoogleMapSurfaceBinding.kt")

        listOf(
            "isMapToolbarEnabled = false",
            "map.isIndoorEnabled = false",
            "map.isBuildingsEnabled = false",
            "isMyLocationButtonEnabled = false",
            "isZoomControlsEnabled = false",
            "isCompassEnabled = true",
            "setOnMarkerClickListener { true }",
            "REASON_GESTURE",
        ).forEach { contract -> assertTrue("missing $contract", binding.contains(contract)) }
        listOf(
            "setOnPoiClickListener",
            "setOnMapClickListener",
            "setOnMapLongClickListener",
            "setPadding(",
        ).forEach { forbidden -> assertFalse("binding contains $forbidden", binding.contains(forbidden)) }
        assertFalse("owner kept the default labels and POIs", binding.contains("setMapStyle"))
    }

    @Test
    fun warmupIsGoogleVariantOnlyAndDeclaredInTheManifest() {
        val manifest = moduleRoot().resolve("src/googlePoc/AndroidManifest.xml").readText()
        val warmup = googleSource("GoogleMapWarmup.kt")

        assertTrue(manifest.contains("androidx.startup.InitializationProvider"))
        assertTrue(manifest.contains("app.trailveil.map.GoogleMapWarmup"))
        assertTrue(warmup.contains("MapsInitializer.initialize"))
        assertFalse(moduleRoot().resolve("src/main/AndroidManifest.xml").readText().contains("GoogleMapWarmup"))
        // androidx.startup rethrows whatever an Initializer throws out of
        // InitializationProvider.onCreate, which kills the process at launch. MapsInitializer
        // absorbs GooglePlayServicesNotAvailableException and some RemoteException ranges, but a
        // delegate-call RemoteException comes back out as an unchecked RuntimeRemoteException.
        // An unguarded warm-up therefore crashes exactly the devices
        // MapProviderUnavailableSurface exists to explain to.
        val guarded = warmup.substringAfter("try {").substringBefore("override fun dependencies")
        assertTrue(
            "the warm-up must not be able to take the process down at launch",
            warmup.contains("try {") &&
                guarded.contains("MapsInitializer.initialize") &&
                guarded.contains("catch (_: Exception)") &&
                guarded.contains("catch (_: LinkageError)"),
        )
    }

    private fun googleSource(name: String): String = moduleRoot()
        .resolve("src/google/java/app/trailveil/map/$name")
        .readText()

    private fun mapLibreSource(): String = moduleRoot()
        .resolve("src/mapLibre/java/app/trailveil/map/TrailVeilMapSurface.kt")
        .readText()

    private fun moduleRoot(): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(cwd, "settings.gradle.kts").isFile) File(cwd, "app") else cwd
    }
}
