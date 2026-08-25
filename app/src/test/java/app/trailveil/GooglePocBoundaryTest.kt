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
    fun GooglePoCManifestOwnsTheKeyMarkerAndLauncher() {
        val manifest = File(moduleRoot(), "src/googlePoc/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("com.google.android.geo.API_KEY"))
        assertTrue(manifest.contains("android:value=\"@string/trailveil_google_maps_poc_api_key\""))
        assertTrue(manifest.contains("android:name=\"org.apache.http.legacy\""))
        assertTrue(manifest.contains("android:required=\"false\""))
        assertTrue(manifest.contains(".googlepoc.GoogleMapsPocActivity"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun mapsDependencyIsScopedToGooglePoCConfiguration() {
        val buildScript = File(repositoryRoot(), "build.gradle.kts")
            .readText()
        val moduleScript = File(moduleRoot(), "build.gradle.kts")
            .readText()

        assertFalse(buildScript.contains("play-services-maps"))
        assertTrue(moduleScript.contains("add(\"googlePocImplementation\", \"com.google.android.gms:play-services-maps:20.0.0\")"))
        assertTrue(moduleScript.contains("debugApiKeySha256"))
        assertFalse(moduleScript.lines().any { line ->
            line.trimStart().startsWith("implementation(\"com.google.android.gms:play-services-maps")
        })
    }

    @Test
    fun ordinaryVariantProductionSourcesContainNoGoogleProviderMarkers() {
        val sourceRoot = File(moduleRoot(), "src")
        val ordinarySourceSets = listOf("main", "debug", "internal", "release", "androidTest")
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
