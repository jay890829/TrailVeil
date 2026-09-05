package app.trailveil.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-005`: the per-variant `TrailVeilMapSurface` actuals share no compiler-enforced contract —
 * the seam is compile-time name resolution — so this test is what makes seam drift fail in a JVM
 * suite instead of only at some variant's compile with a confusing unresolved-reference error.
 *
 * The guard pins three facts:
 *  - exactly the expected actuals exist, and none is left behind in `src/main`;
 *  - EVERY actual's neutral parameter list matches the single pinned contract below — each actual
 *    equals the pin, so all actuals equal each other; that transitivity IS the cross-actual
 *    signature comparison (provider-specific `*ForTesting` seams are deliberately NOT pinned:
 *    each actual may add its own, per the accepted design);
 *  - the shared contract file stays provider-free.
 *
 * Stage 1 established the guard with the MapLibre actual as the only side; stage 2 added the
 * googlePoc actual to [expectedActualDirectories], which arms the comparison.
 */
class MapSurfaceNeutralSignatureTest {

    /**
     * The neutral surface parameters, in declaration order, with their defaults. Provider-specific
     * test seams come after these and are not part of the neutral contract.
     */
    private val neutralParameters = listOf(
        "modifier: Modifier = Modifier",
        "provider: MapProviderConfiguration = ProductionMapProvider",
        "fallbackTimeoutMillis: Long = 5_000L",
        "savedStateKey: String = \"trailveil.map.primary\"",
        "fogRuntime: FogRuntime? = null",
        "fogRequired: Boolean = false",
        "rendersIntoTheWindow: Boolean = false",
        "cameraRequest: MapCameraRequest? = null",
        "currentLocation: GeoPoint? = null",
        "followLocation: GeoPoint? = null",
        "compassTopInset: Dp = MAP_CONTROL_INSET",
        "compassEndInset: Dp = MAP_CONTROL_INSET",
        "trackOverlay: MapTrackOverlay? = null",
        "onUserMovedCamera: () -> Unit = {}",
        "onFogRendered: ((FogViewportRender) -> Unit)? = null",
        "onFogFailure: (Throwable) -> Unit = {}",
    )

    // `V02-008`: the Google actual lives in `src/google`, the tree both Google build types
    // compile. `src/googlePoc` is the engineering harness and declares no actual.
    private val expectedActualDirectories = listOf("mapLibre", "google")

    @Test
    fun everyExpectedActualExistsAndNoneRemainsInMain() {
        val src = moduleRoot().resolve("src")
        assertTrue(
            "src/main must not define TrailVeilMapSurface.kt after the stage-1 split",
            !src.resolve("main/java/app/trailveil/map/TrailVeilMapSurface.kt").isFile,
        )
        expectedActualDirectories.forEach { variantDirectory ->
            assertTrue(
                "missing TrailVeilMapSurface actual for source dir $variantDirectory",
                src.resolve("$variantDirectory/java/app/trailveil/map/TrailVeilMapSurface.kt").isFile,
            )
        }
    }

    @Test
    fun everyActualDeclaresThePinnedNeutralParametersInOrderBeforeAnyTestSeam() {
        expectedActualDirectories.forEach { variantDirectory ->
            val declaredNeutral = signatureLines(variantDirectory)
                .filter { line -> !line.startsWith("//") }
                .takeWhile { line -> !line.contains("ForTesting") }
            assertEquals(
                "the $variantDirectory actual's neutral parameter list drifted from the pinned " +
                    "contract; every actual must keep the identical neutral prefix",
                neutralParameters,
                declaredNeutral,
            )
        }
    }

    @Test
    fun providerSpecificTestSeamsComeAfterEveryNeutralParameter() {
        expectedActualDirectories.forEach { variantDirectory ->
            val lines = signatureLines(variantDirectory)
            val firstSeam = lines.indexOfFirst { it.contains("ForTesting") }
            // Anchor on "name:" — a bare-prefix match would let a seam like
            // providerStartupDecisionForTesting shadow the neutral parameter `provider`.
            val lastNeutral = lines.indexOfLast { line ->
                neutralParameters.any { neutral ->
                    line.startsWith(neutral.substringBefore(":") + ":")
                }
            }
            assertTrue(
                "the $variantDirectory actual declares no test seam; update this guard if that " +
                    "was intended",
                firstSeam >= 0,
            )
            assertTrue(
                "the $variantDirectory actual declares a neutral parameter after a *ForTesting " +
                    "seam; neutral parameters must stay a contiguous prefix so the actuals can " +
                    "be compared mechanically",
                lastNeutral < firstSeam || lastNeutral == -1,
            )
        }
    }

    private fun signatureLines(variantDirectory: String): List<String> =
        actualSource(variantDirectory)
            .substringAfter("internal fun TrailVeilMapSurface(")
            .substringBefore("\n) {")
            .lines()
            .map { line -> line.trim().trimEnd(',') }
            .filter(String::isNotEmpty)

    @Test
    fun theSharedContractFileNamesNoProvider() {
        val contract = moduleRoot()
            .resolve("src/main/java/app/trailveil/map/MapSurfaceContract.kt")
            .readText()
        listOf("maplibre", "openfreemap", "Google", "play-services", "com.google").forEach { marker ->
            assertTrue(
                "MapSurfaceContract.kt must stay provider-neutral but contains '$marker'",
                !contract.contains(marker, ignoreCase = true),
            )
        }
    }

    private fun actualSource(variantDirectory: String): String = moduleRoot()
        .resolve("src/$variantDirectory/java/app/trailveil/map/TrailVeilMapSurface.kt")
        .readText()

    private fun moduleRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(workingDirectory, "settings.gradle.kts").isFile) {
            File(workingDirectory, "app")
        } else {
            workingDirectory
        }
    }
}
