package app.trailveil

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePocBoundaryTest {
    @Test
    fun ordinaryManifestDoesNotDeclareTheGoogleMapsKeyMarker() {
        val manifest = File(moduleRoot(), "src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("com.google.android.geo.API_KEY"))
    }

    /**
     * `V02-008`: a build type contributes exactly one manifest, so the harness build type's
     * manifest repeats the shared Google entries rather than merging them. That duplication is
     * cheap and legible, but it can drift, so it is asserted rather than trusted: every shared
     * entry must appear in BOTH files, and the engineering Activities in the harness one alone.
     * `verifyGooglePocMergedManifest` makes the same claim on the merged manifests AGP produces.
     */
    @Test
    fun bothGoogleManifestsCarryTheSharedEntriesAndOnlyTheHarnessOneCarriesTheHarness() {
        val shared = File(moduleRoot(), "src/google/AndroidManifest.xml").readText()
        val harness = File(moduleRoot(), "src/googlePoc/AndroidManifest.xml").readText()
        listOf(
            "com.google.android.geo.API_KEY",
            "android:value=\"@string/trailveil_google_maps_poc_api_key\"",
            "android:name=\"org.apache.http.legacy\"",
            "app.trailveil.map.GoogleMapWarmup",
        ).forEach { entry ->
            assertTrue("src/google manifest is missing $entry", shared.contains(entry))
            assertTrue("src/googlePoc manifest is missing $entry", harness.contains(entry))
        }
        listOf(
            ".googlepoc.GoogleMapsPocActivity",
            ".map.GoogleMapSurfaceTestActivity",
        ).forEach { harnessActivity ->
            assertTrue(
                "src/googlePoc manifest lost $harnessActivity",
                harness.contains(harnessActivity),
            )
            assertFalse(
                "src/google manifest declares the engineering Activity $harnessActivity",
                shared.contains(harnessActivity),
            )
        }
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

    /**
     * The one string element of `src/googlePoc` copy allowed to name the other provider.
     *
     * `V02-008` acceptance: every terminal reason belonging to this provider points the user at
     * the build that does not have it, and a pointer that will not say where to go is not a
     * pointer. The exception is exactly this: localized user copy, in a resource whose own NAME
     * carries no marker, so nothing in `src/googlePoc` code can reach for the other provider and
     * claim this clause.
     */
    private val keylessBuildPointer = "map_provider_unavailable_keyless_build"

    @Test
    fun googlePoCSourcesContainNoOtherProviderMarkers() {
        // The inverse boundary: near Google content no other basemap may render, so no MapLibre
        // or OpenFreeMap marker may appear anywhere in EITHER Google source tree, save the one
        // documented copy element above. `V02-008` split them - `src/google` is the production
        // half both Google build types compile, `src/googlePoc` the harness half only one does -
        // and both are scanned, so moving a marker across the split escapes nothing. The overlay
        // manifests are exempt as files: `V02-008` moved the notices meta-data into the MapLibre
        // source set, so neither names a provider today, but the exemption predates that and is
        // cheaper to keep than to re-earn.
        val googleRoots = listOf("src/google", "src/googlePoc").map { File(moduleRoot(), it) }
        val forbiddenMarkers = listOf("maplibre", "openfreemap")
        val pointerElement = Regex(
            """<string [^>]*name="$keylessBuildPointer"[^>]*>[^<]*</string>""",
        )
        val offenders = googleRoots.asSequence()
            .flatMap { root -> root.walkTopDown() }
            .filter { file ->
                file.isFile &&
                    file.extension in setOf("kt", "java", "xml", "json") &&
                    file.name != "AndroidManifest.xml"
            }
            .flatMap { file ->
                // Only that element's own text is exempt, and only where strings live. Everything
                // else in the same file is still scanned, so a second marker cannot hide behind
                // the first one's licence.
                val text = if (file.name == "strings.xml") {
                    pointerElement.replace(file.readText(), "")
                } else {
                    file.readText()
                }
                forbiddenMarkers
                    .filter { marker -> text.contains(marker, ignoreCase = true) }
                    .map { marker -> "${file.relativeTo(File(moduleRoot(), "src"))}: $marker" }
            }
            .toList()

        assertTrue("non-Google provider markers leaked into the Google trees: $offenders", offenders.isEmpty())
    }

    /**
     * The positive control for the exemption above: a blind reader that stripped the wrong thing,
     * or copy that quietly stopped naming a destination, would otherwise pass the marker test by
     * having nothing to find. Every locale must carry the pointer, and it must name the build.
     */
    @Test
    fun everyLocaleOfTheGoogleVariantNamesTheBuildItPointsAt() {
        val stringFiles = File(moduleRoot(), "src/google/res").walkTopDown()
            .filter { it.isFile && it.name == "strings.xml" }
            .toList()
        assertTrue("no Google string resources found under src/google/res", stringFiles.size >= 2)
        stringFiles.forEach { file ->
            val pointer = Regex(
                """<string [^>]*name="$keylessBuildPointer"[^>]*>([^<]*)</string>""",
            ).find(file.readText())
            assertTrue("${file.parentFile.name} has no $keylessBuildPointer", pointer != null)
            assertTrue(
                "${file.parentFile.name} pointer does not name the OpenFreeMap build",
                pointer!!.groupValues[1].contains("OpenFreeMap"),
            )
        }
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
        // Every dependency line that names an engine must be one whose configuration is spelled
        // from a build-type variable - `add("{variant}Implementation", ...)` inside one of the two
        // forEach loops asserted above. `implementation(`, `add("implementation", ...)`,
        // `debugImplementation(` and `debugApi(` all fail it, which the previous prefix match let
        // through (V02-008 verifier note). It is a line filter, not a parser: a string-invoked
        // `"implementation"(...)`, a declaration split across lines, or the right spelling inside
        // the WRONG loop all pass it (measured by the delta verifier). Those land in the APK and
        // fail the packaged-APK boundary check, which is the gate this task rests on; this test is
        // the fast one that names the line. Comment lines are skipped so the build script may
        // explain the rule in prose without tripping it.
        val engineMarkers = listOf("libs.maplibre", "org.maplibre", "play-services-maps")
        val dependencyShapes = listOf("add(", "implementation(", "api(", "compileOnly(", "runtimeOnly(")
        val offenders = moduleScript.lines()
            .map(String::trim)
            .filter { line -> !line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*") }
            .filter { line -> engineMarkers.any(line::contains) }
            // Case-insensitive, because the configuration prefix capitalises the shape:
            // `debugImplementation(` and `debugApi(` are the evasions this exists to catch, and a
            // case-sensitive match let the first of them straight through (proven by mutation).
            .filter { line -> dependencyShapes.any { shape -> line.contains(shape, ignoreCase = true) } }
            .filter { line -> !line.contains("{variant}Implementation") }
        assertTrue("a map engine is on a shared configuration: $offenders", offenders.isEmpty())
    }

    /**
     * The two Google build types read two different key properties, and neither falls back.
     *
     * `V02-013` publishes the release-configured Google APK, so the key it compiles in is public
     * by construction and its only protection is being restricted to the release certificate and
     * this package. Both build types used to read `debugApiKey`, which is how the owner's first
     * Google release build shipped a key restricted to the debug certificate and could not
     * authorize. A fallback would restore that failure quietly, so the mapping is asserted to be
     * exhaustive rather than defaulted.
     *
     * This is a source assertion, and it says which line is wrong; what proves the shipped APK is
     * `verifyGooglePocMergedManifest`, which reads the value each variant actually resolved.
     */
    @Test
    fun eachGoogleBuildTypeReadsItsOwnKeyProperty() {
        val moduleScript = File(moduleRoot(), "build.gradle.kts").readText()

        assertTrue(
            "the PoC build type must name the debug-certificate key property",
            moduleScript.contains("\"googlePoc\" -> \"debugApiKey\""),
        )
        assertTrue(
            "the release build type must name the release-certificate key property",
            moduleScript.contains("\"googleRelease\" -> \"releaseApiKey\""),
        )
        // An `else ->` that returns a property name is the fallback this test exists to forbid; an
        // unmapped build type must fail the build instead of borrowing the other one's key.
        val mapping = Regex(
            """fun googleKeyPropertyFor\(buildType: String\): String = when \(buildType\) \{([\s\S]*?)\n\}""",
        ).find(moduleScript)
        assertTrue("googleKeyPropertyFor is missing", mapping != null)
        assertTrue(
            "an unmapped build type must not resolve to a key property",
            mapping!!.groupValues[1].contains("else -> error("),
        )

        // Each build type passes its own name, so the mapping above is the only place the two
        // properties are chosen.
        assertTrue(
            moduleScript.contains("applyGoogleMapsKey(buildType = \"googlePoc\")"),
        )
        assertTrue(
            moduleScript.contains("applyGoogleMapsKey(buildType = \"googleRelease\")"),
        )
        assertFalse(
            "applyGoogleMapsKey() without a build type would share one key again",
            moduleScript.contains("applyGoogleMapsKey()"),
        )

        // The reader takes the property, and the fingerprint check follows it, so a release key's
        // typo self-check cannot be satisfied by the debug key's fingerprint.
        assertTrue(moduleScript.contains("properties.getProperty(keyProperty)?.trim()"))
        assertTrue(moduleScript.contains(".getProperty(keyProperty + \"Sha256\")"))
        assertFalse(moduleScript.contains("getProperty(\"debugApiKey\")"))
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

    /**
     * `V03-011` arm 1: the ring knob is a harness fixture and must not exist in a published build.
     *
     * Same seam idiom as `googleMapOverlayObservationSeam` - the function is declared once per
     * Google build type and the shared binding calls it - so this asserts the shape rather than the
     * behaviour: `googleRelease` returns a constant and declares no mutable state at all, while
     * `googlePoc` carries the selector. An arm object compiled into a release APK would change how
     * much fog a real user's map renders.
     */
    @Test
    fun onlyTheHarnessBuildTypeCanSelectAFogCoverageArm() {
        val release = File(
            moduleRoot(),
            "src/googleRelease/java/app/trailveil/map/GoogleFogCoverageProfileSeam.kt",
        ).readText()
        val harness = File(
            moduleRoot(),
            "src/googlePoc/java/app/trailveil/map/GoogleFogCoverageProfileSeam.kt",
        ).readText()

        listOf(release, harness).forEach { seam ->
            assertTrue(
                "both build types must declare the same seam symbol, or a variant compiles neither",
                seam.contains("internal fun googleFogCoverageProfile(): GoogleFogCoverageProfile"),
            )
        }
        assertTrue(
            "the release seam must be the shipped constant",
            release.contains("GoogleFogCoverageProfile.DEFAULT"),
        )
        assertFalse(
            "the release seam must declare no mutable state: $release",
            release.contains("var ") || release.contains("@Volatile"),
        )
        assertTrue(
            "the harness seam is where the selector lives",
            harness.contains("object GoogleFogCoverageArm") && harness.contains("@Volatile"),
        )
    }

    /**
     * The asymmetry `FogPaddingRingSurroundTest` proved is load-bearing, pinned where it is wired.
     *
     * Padding the published set and the predicted set by the same amount cancels exactly, so a ring
     * bought with one shared planner is rendered, paid for, and buys no camera movement. The two
     * planners are therefore not interchangeable, and the surround predicate in particular must
     * never be handed the padded one. Asserted on the source because the difference is invisible in
     * every shipped build, where both planners are identical.
     */
    @Test
    fun theSurroundTestIsNeverHandedThePaddedPlanner() {
        val binding = File(
            moduleRoot(),
            "src/google/java/app/trailveil/map/GoogleCanonicalFogSurfaceBinding.kt",
        ).readText()

        assertTrue(
            "the surround predicate must predict without the ring",
            binding.contains("planner = surroundPlanner,"),
        )
        assertFalse(
            "the surround predicate must never be handed the render planner",
            binding.contains("planner = renderPlanner,"),
        )
        assertFalse(
            "the binding must take its planners from the profile seam, not construct its own",
            binding.contains("FogViewportCoveragePlanner("),
        )
        assertTrue(
            "every budget a ring pushes on must travel with the profile. The window renderer's " +
                "own bound is one of them: the padded PLAN reaches it in the same union as the " +
                "SDK's observed requests, and its planner refuses an oversized set before a tile " +
                "is rendered",
            binding.contains("maxTiles = coverageProfile.maxRequestedKeys,"),
        )
        assertEquals(
            "exactly one call site renders the ring: the published key set",
            1,
            binding.split("renderPlanner.plan(").size - 1,
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

    /**
     * `V03-011` arm 2's surface must be ABSENT from a published build, not merely unselected.
     *
     * Prototype A replaces the fog's `TileOverlay` with one anchored image. The tempting place for
     * its selector was a `surfaceKind` field on `GoogleFogCoverageProfile` - and that file is in
     * `src/google`, which every Google build type compiles, so the enum value, the field and its
     * factory would all have shipped. This repository already ruled on that shape once:
     * `GoogleMapOverlayObservationSeam` records moving a null-by-default, cross-process-unreachable
     * hook out of `src/google` because it was scaffolding in a public artifact all the same.
     *
     * So the choosing lives in a per-build-type seam. This asserts the three things that make the
     * absence structural rather than assertional: both build types declare the same symbol, the
     * release twin cannot name an implementation, and the implementation is in a source set the
     * release variant does not compile.
     *
     * It also pins the fallback direction. A seam shaped "return the surface or null" invites a
     * null that means NO surface, which on a real device is an unfogged basemap. Null here means
     * the shipped tile path, and the binding's own signature is what makes that true.
     */
    @Test
    fun onlyTheHarnessBuildTypeCanSelectTheMosaicFogSurface() {
        val release = File(
            moduleRoot(),
            "src/googleRelease/java/app/trailveil/map/GoogleFogOverlayInstallerSeam.kt",
        ).readText()
        val harness = File(
            moduleRoot(),
            "src/googlePoc/java/app/trailveil/map/GoogleFogOverlayInstallerSeam.kt",
        ).readText()

        listOf(release, harness).forEach { seam ->
            assertTrue(
                "both build types must declare the same seam symbol, or a variant compiles neither",
                seam.contains("internal fun googleFogOverlayInstaller("),
            )
        }
        assertTrue(
            "the release seam must return null unconditionally",
            release.contains("): GoogleFogOverlayInstaller? = null"),
        )
        assertFalse(
            "the release seam must not be able to name an implementation at all: $release",
            release.contains("GoogleFogMosaicOverlayInstaller"),
        )
        assertFalse(
            "the release seam must declare no mutable state: $release",
            release.contains("var ") || release.contains("@Volatile"),
        )
        assertTrue(
            "the harness seam is where the selector lives",
            harness.contains("GoogleFogCoverageArm.mosaicOverlay") &&
                harness.contains("GoogleFogMosaicOverlayInstaller("),
        )

        // The implementation itself is in a source set googleRelease does not compile, which is
        // what makes the absence a property of the artifact rather than of this assertion.
        assertTrue(
            "the mosaic installer must live in src/googlePoc",
            File(
                moduleRoot(),
                "src/googlePoc/java/app/trailveil/map/GoogleFogMosaicOverlayInstaller.kt",
            ).isFile,
        )
        assertFalse(
            "no mosaic surface may exist in src/google, which every Google build type compiles",
            File(moduleRoot(), "src/google/java/app/trailveil/map")
                .listFiles()
                .orEmpty()
                .any { file -> file.name.contains("MosaicOverlayInstaller") },
        )

        // Null means the shipped surface, never no surface.
        val binding = File(
            moduleRoot(),
            "src/google/java/app/trailveil/map/GoogleCanonicalFogSurfaceBinding.kt",
        ).readText()
        assertTrue(
            "the binding must take the installer as a nullable argument defaulting to none, so a " +
                "null seam is the tile path rather than an unfogged map",
            binding.contains("private val overlayInstaller: GoogleFogOverlayInstaller? = null,"),
        )
    }

    private fun moduleRoot(): File = File(repositoryRoot(), "app")
}
