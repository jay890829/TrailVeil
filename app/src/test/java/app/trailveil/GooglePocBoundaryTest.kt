package app.trailveil

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePocBoundaryTest {
    @Test
    fun ordinaryManifestDoesNotDeclareTheGoogleMapsKeyMarker() {
        val manifest = File(moduleRoot(), "src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("com.google.android.geo.API_KEY"))
    }

    @Test
    fun GooglePoCManifestOwnsTheKeyMarkerAndDefersTheLauncherToProduction() {
        val manifest = File(moduleRoot(), "src/googlePoc/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("com.google.android.geo.API_KEY"))
        assertTrue(manifest.contains("android:value=\"@string/trailveil_google_maps_poc_api_key\""))
        assertTrue(manifest.contains("android:name=\"org.apache.http.legacy\""))
        assertTrue(manifest.contains("android:required=\"false\""))
        // `V02-005` stage 2, the launcher inversion: the PoC Activity survives only as a
        // de-launchered, unexported engineering harness, and the overlay must no longer touch
        // MainActivity at all — the production launcher merges in from `src/main` untouched.
        assertTrue(manifest.contains(".googlepoc.GoogleMapsPocActivity"))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertFalse(manifest.contains("android.intent.category.LAUNCHER"))
        // The element, not the word: the overlay's comment may narrate the inversion, but no
        // MainActivity NODE may exist here in any form (redeclaration or removal directive).
        assertFalse(manifest.contains("android:name=\".MainActivity\""))
        assertFalse(manifest.contains("android:name=\"app.trailveil.MainActivity\""))
    }

    @Test
    fun googlePoCSourcesContainNoOtherProviderMarkers() {
        // The inverse boundary: near Google content no other basemap may render, so no MapLibre
        // or OpenFreeMap marker may appear anywhere in the googlePoc source tree. The overlay
        // manifest is exempt because it legitimately NAMES the MapLibre notices meta-data in a
        // `tools:node="remove"` directive — the one place the string means "keep this out".
        val googlePocRoot = File(moduleRoot(), "src/googlePoc")
        val forbiddenMarkers = listOf("maplibre", "openfreemap")
        val offenders = googlePocRoot.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension in setOf("kt", "java", "xml", "json") &&
                    file.name != "AndroidManifest.xml"
            }
            .flatMap { file ->
                val text = file.readText()
                forbiddenMarkers
                    .filter { marker -> text.contains(marker, ignoreCase = true) }
                    .map { marker -> "${file.relativeTo(googlePocRoot)}: $marker" }
            }
            .toList()

        assertTrue("non-Google provider markers leaked into src/googlePoc: $offenders", offenders.isEmpty())
    }

    /**
     * Neither map engine may be linked by a configuration that reaches the other provider.
     *
     * `V02-008` rewrote this from naming one literal dependency line to asserting the property that
     * line was standing in for, because the property was not actually held: `play-services-maps`
     * was correctly scoped while `implementation(libs.maplibre.opengl)` was not, so the Google
     * build packaged four `libmaplibre.so` and 1657 `Lorg/maplibre/` class descriptors. A source
     * scan cannot see a Gradle configuration, which is why the packaged-APK gate exists too; this
     * test is the fast one that says which line is wrong.
     */
    @Test
    fun neitherMapEngineIsLinkedByASharedConfiguration() {
        val buildScript = File(repositoryRoot(), "build.gradle.kts").readText()
        val moduleScript = File(moduleRoot(), "build.gradle.kts").readText()

        assertFalse(buildScript.contains("play-services-maps"))
        assertFalse(buildScript.contains("maplibre"))
        assertTrue(moduleScript.contains("debugApiKeySha256"))

        // Each engine is added by iterating its own build-type list, and by nothing else.
        assertTrue(
            "the Maps SDK must be added from googleBuildTypes",
            moduleScript.contains("googleBuildTypes.forEach { variant ->"),
        )
        assertTrue(
            "MapLibre must be added from openFreeMapBuildTypes",
            moduleScript.contains(
                "openFreeMapBuildTypes.forEach { variant -> add(\"\${variant}Implementation\", libs.maplibre.opengl) }",
            ),
        )
        val sharedConfigurations = listOf("implementation", "api", "compileOnly", "runtimeOnly")
        val offenders = moduleScript.lines()
            .map(String::trim)
            .filter { line ->
                sharedConfigurations.any { configuration ->
                    line.startsWith("$configuration(") &&
                        (line.contains("maplibre") || line.contains("play-services-maps"))
                }
            }
        assertTrue("a map engine is on a shared configuration: $offenders", offenders.isEmpty())
    }

    @Test
    fun ordinaryVariantProductionSourcesContainNoGoogleProviderMarkers() {
        val sourceRoot = File(moduleRoot(), "src")
        val ordinarySourceSets = listOf(
            "main",
            "debug",
            "internal",
            "release",
            "androidTest",
            // `V02-005` stage 1: the MapLibre actual and the MapLibre-coupled instrumentation
            // moved into their own source trees; both stay ordinary and Google-free.
            "mapLibre",
            "androidTestDebug",
        )
        val forbiddenMarkers = listOf(
            "com.google.android.geo.API_KEY",
            "com.google.android.gms:play-services-maps",
            "play-services-maps",
            "GoogleMap",
            "GoogleMaps",
            "mapId",
            "map_id",
            "map-id",
        )
        val offenders = ordinarySourceSets
            .map { sourceSet -> File(sourceRoot, sourceSet) }
            .filter(File::exists)
            .flatMap { directory ->
                directory.walkTopDown()
                    .filter { file -> file.isFile && file.extension in setOf("kt", "java", "xml") }
                    .flatMap { file ->
                        val text = file.readText()
                        forbiddenMarkers
                            .filter(text::contains)
                            .map { marker -> "${file.relativeTo(sourceRoot)}: $marker" }
                    }
                    .toList()
            }

        assertTrue("Google markers leaked into ordinary sources: $offenders", offenders.isEmpty())
    }

    /**
     * `compose.ui.test.manifest` is a `debugImplementation`, and it is the only thing that declares
     * the bare `androidx.activity.ComponentActivity` that `createComposeRule()` launches. A case
     * left in the shared `src/androidTest` tree therefore compiles into the googlePoc AndroidTest
     * APK and dies there with "Unable to resolve activity for ... ComponentActivity" — which is
     * exactly how `FogRevealLatencyTest` failed the first full googlePoc device run, having been the
     * one host-free Compose case the stage-1 split left behind while moving its three siblings.
     * Such a case belongs in `src/androidTestDebug`; provider-neutral coverage does not need to run
     * twice.
     */
    @Test
    fun sharedInstrumentationNeedsNoDebugOnlyComposeActivityHost() {
        val shared = File(moduleRoot(), "src/androidTest")
        val offenders = shared.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("createComposeRule()") || text.contains("ComponentActivity::class")
            }
            .map { file -> file.relativeTo(shared).path }
            .toList()

        assertTrue(
            "these need the debug-only Compose test manifest and cannot run under googlePoc: " +
                "$offenders",
            offenders.isEmpty(),
        )
    }

    private fun repositoryRoot(): File =
        File(requireNotNull(System.getProperty("user.dir"))).let { workingDirectory ->
            if (File(workingDirectory, "settings.gradle.kts").isFile) {
                workingDirectory
            } else {
                requireNotNull(workingDirectory.parentFile)
            }
        }

    private fun moduleRoot(): File = File(repositoryRoot(), "app")
}
