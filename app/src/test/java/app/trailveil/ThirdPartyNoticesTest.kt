package app.trailveil

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-016`: the Google build's third-party notices are a checked-in file, so something has to fail
 * when it stops being true.
 *
 * Both providers answer `providerThirdPartyNotices` from a raw resource in their own source set,
 * and MapLibre's is a file the SDK ships. Google publishes its notices too, but at the ROOT of each
 * Play services AAR rather than under `res/`, so nothing carries them into the APK by itself; the
 * `oss-licenses-plugin` exists to harvest them and was not taken. Harvesting them once by hand
 * leaves exactly one failure mode: the dependency moves and the file does not.
 *
 * So this asserts what a human cannot be relied on to notice. Every `com.google.android.gms`
 * artifact the build declares must be named in the notices file's own covered-artifacts header,
 * VERSION INCLUDED - a version bump changes which notices apply and is the likeliest way this goes
 * stale. Transitives may additionally appear there and are not required to be declared; the file
 * covers `play-services-base`, `-basement` and `-tasks` because `play-services-maps` pulls them.
 *
 * It also pins the boundary and the shape, because a notices file that is empty, truncated, or in
 * the shared source set would satisfy a naive check and fail the user.
 */
class ThirdPartyNoticesTest {

    @Test
    fun everyDeclaredGoogleDependencyIsNamedInTheCheckedInNotices() {
        val declared = DECLARED_GMS.findAll(buildScript())
            .map { match -> match.groupValues[1] }
            .toSortedSet()
        assertTrue(
            "the build script declares no com.google.android.gms artifact at all, so this test " +
                "would pass by having nothing to check. Either the Google variant lost its map " +
                "dependency or the pattern below stopped matching how it is declared.",
            declared.isNotEmpty(),
        )
        val notices = noticesFile().readText()
        val missing = declared.filterNot { artifact -> notices.contains(artifact) }
        assertEquals(
            "these Google dependencies are declared by the build and are NOT named in " +
                "src/google/res/raw/google_third_party_notices.txt, so the published Google APK " +
                "would show notices that do not cover what it links against. Regenerate the file " +
                "from the AARs' own third_party_licenses.txt/.json as V02-016's evidence records, " +
                "then update its covered-artifacts header",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun theNoticesAreGoogleTextInTheGoogleSourceSetAndNotASummary() {
        val notices = noticesFile()
        assertTrue(
            "the notices must live in src/google/res/raw so the MapLibre APK cannot package them, " +
                "which is the V02-008 defect the provider split exists to prevent: " +
                notices.absolutePath,
            notices.absolutePath.replace('\\', '/').contains("/src/google/res/raw/"),
        )
        val text = notices.readText()
        assertTrue(
            "the notices file is ${text.length} characters, under the " +
                "$MINIMUM_NOTICE_CHARACTERS a real set of licence texts runs to. A truncated or " +
                "placeholder file would satisfy every other assertion here while showing the user " +
                "nothing",
            text.length >= MINIMUM_NOTICE_CHARACTERS,
        )
        // Verbatim licence text, not a summary of it: these are the opening words of the two
        // licences every entry in the harvested set is under.
        assertTrue(
            "the notices must contain the licence texts themselves rather than their names",
            text.contains("Apache License") && text.contains("The MIT License"),
        )
        assertTrue(
            "the file must say where its text came from, or the next reader cannot regenerate it",
            text.contains("third_party_licenses.txt") && text.contains("third_party_licenses.json"),
        )
    }

    @Test
    fun bothProvidersAnswerTheSameNoticesSymbolFromTheirOwnSourceSet() {
        val google = File(moduleRoot(), "src/google/java/app/trailveil/map/ProviderThirdPartyNotices.kt")
        val mapLibre = File(moduleRoot(), "src/mapLibre/java/app/trailveil/map/ProviderThirdPartyNotices.kt")
        listOf(google, mapLibre).forEach { file ->
            assertTrue("missing provider notices source: ${file.absolutePath}", file.isFile)
            assertTrue(
                "both providers must declare the same symbol, or a variant compiles neither: " +
                    file.absolutePath,
                file.readText().contains("internal fun providerThirdPartyNotices(context: Context)"),
            )
        }
        assertTrue(
            "no notices implementation may exist in src/main, which both variants compile",
            !File(moduleRoot(), "src/main/java/app/trailveil/map/ProviderThirdPartyNotices.kt").exists(),
        )
        // Each reads its OWN provider's resource. Crossing them would package one provider's
        // material into the other's APK by the back door of a shared resource name.
        assertTrue(
            "the Google seam must read the Google notices resource",
            google.readText().contains("R.raw.google_third_party_notices"),
        )
        assertTrue(
            "the MapLibre seam must read the MapLibre notices resource",
            mapLibre.readText().contains("R.raw.maplibre_third_party_notices"),
        )
    }

    private fun buildScript(): String = File(moduleRoot(), "build.gradle.kts").readText()

    private fun noticesFile(): File =
        File(moduleRoot(), "src/google/res/raw/google_third_party_notices.txt")

    private fun moduleRoot(): File = File(requireNotNull(System.getProperty("user.dir")))

    private companion object {
        val DECLARED_GMS = Regex("""["'](com\.google\.android\.gms:[\w.-]+:[\w.-]+)["']""")

        /**
         * A floor rather than an exact size: the file is 388 KB of Google's own text today and any
         * dependency bump moves it, so pinning the length would fail for the right reason at the
         * wrong time. 100 KB is far below the smallest honest harvest - one Apache 2.0 copy alone
         * is 11 KB - and far above any placeholder.
         */
        const val MINIMUM_NOTICE_CHARACTERS = 100_000
    }
}
