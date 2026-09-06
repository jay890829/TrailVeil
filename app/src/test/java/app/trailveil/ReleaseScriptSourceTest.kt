package app.trailveil

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `V02-013`: what the release script must keep asserting about the two published APKs.
 *
 * The script's own audits are the real gate, because they read the packaged artifact rather than
 * the sources that produced it. They run only on the owner's machine at release time, though, so
 * nothing else in the repository notices when one of them is deleted. This test is the cheap guard
 * against that: it does not re-audit anything, it asserts that each refusal is still written down.
 *
 * The Google APK is the reason this matters now. It is the first published artifact that must
 * CONTAIN a credential, so its audit is the mirror image of the OpenFreeMap one, and a deleted line
 * here does not fail a build - it publishes a keyless APK, or a debug-signed one whose key cannot
 * authorize, and nobody finds out until someone installs it.
 */
class ReleaseScriptSourceTest {
    @Test
    fun `both published APKs are built from one invocation of one commit`() {
        val script = releaseScript()

        // Two Gradle invocations could assemble two APKs from two different trees, and every
        // downstream digest would still agree with itself.
        val taskLine = script.lineSequence().single { line -> line.contains("\$gradleTasks = @(") }
        listOf("clean", "assembleRelease", "assembleInternal", "lintRelease", "testDebugUnitTest")
            .forEach { task -> assertTrue("release gate lost $task", taskLine.contains("'$task'")) }
        assertTrue(
            "the Google APK must be assembled in the same invocation",
            script.contains("\$gradleTasks += @('assembleGoogleRelease', 'lintGoogleRelease')"),
        )
        assertTrue(
            "the worktree must be clean before the build",
            script.contains("Refusing to build a release from a dirty worktree"),
        )
        assertTrue(
            "the worktree must be unchanged after the build",
            script.contains("Worktree changed during the release build"),
        )
    }

    @Test
    fun `the OpenFreeMap APK still refuses every Google marker`() {
        val script = releaseScript()

        // `V02-013` publishes a Google APK; it does not relax what the OpenFreeMap APK may contain.
        // These three are independent: the manifest marker, the packaged SDK, and the key resource.
        assertTrue(
            script.contains("the candidate APK declares the Google Maps API-key marker"),
        )
        assertTrue(
            script.contains("the candidate APK packages the Google Maps SDK"),
        )
        assertTrue(
            script.contains("the candidate APK compiles the Google Maps key resource"),
        )
        assertTrue(
            script.contains("the candidate APK contains a Google API key-shaped string"),
        )
        assertTrue(
            "the OpenFreeMap APK must still be proven to be the OpenFreeMap variant",
            script.contains("the candidate APK does not package MapLibre"),
        )
    }

    @Test
    fun `the Google APK is audited for what it must contain`() {
        val script = releaseScript()

        // A keyless Google build is a legitimate build - it fails closed onto the
        // provider-unavailable surface - and is exactly the artifact that must never be published.
        assertTrue(
            "a sentinel-carrying Google APK must be refused",
            script.contains("it carries the missing-key sentinel"),
        )
        assertTrue(
            "the Google APK must carry exactly one key-shaped string",
            script.contains("must compile exactly one key-shaped string"),
        )
        assertTrue(
            "the key resource must be defined exactly once",
            script.contains("must define trailveil_google_maps_poc_api_key exactly once"),
        )
        // Presence of a marker and presence of a key prove nothing on their own; what the app needs
        // is the marker pointing AT the key, which is why the resource id is resolved and matched.
        assertTrue(
            "the key marker must be proven to reference the key resource",
            script.contains("its API-key marker does not reference the key resource"),
        )
        assertTrue(script.contains("it does not declare the Google Maps API-key marker"))
        assertTrue(script.contains("it does not package the Google Maps SDK"))
        assertTrue(
            "the two providers stay exclusive in both directions",
            script.contains("it packages MapLibre, so the two providers are not exclusive"),
        )
        assertTrue(script.contains("it packages the MapLibre native library"))
        assertTrue(script.contains("it packages the MapLibre third-party notices"))
        assertTrue(
            "the engineering Activities must not reach a published Google APK",
            script.contains("it carries the engineering class") &&
                script.contains("it declares the engineering component"),
        )
    }

    @Test
    fun `the packaged Google key is proven to be the intended key, by fingerprint`() {
        val script = releaseScript()

        // Every other key check is about shape, and shape cannot tell one valid key from another.
        // Publishing the wrong one means publishing a build that cannot authorize, or someone
        // else's credential.
        assertTrue(
            "publishing must require the release key's fingerprint",
            script.contains("Publishing a Google APK requires releaseApiKeySha256"),
        )
        assertTrue(
            "the packaged key must be compared against that fingerprint",
            script.contains("does not match the configured releaseApiKeySha256"),
        )
        assertTrue(
            "the fingerprint must be read from the same external file the build reads",
            script.contains("\$env:TRAILVEIL_GOOGLE_MAPS_PROPERTIES") &&
                script.contains("'.trailveil/maps/google-maps.properties'"),
        )
        // The comparison is on a digest, and no published file carries the key or anything derived
        // from it beyond the fact that the check passed.
        assertTrue(
            script.contains("\$packagedKeyFingerprint -ne \$expectedKeyFingerprint"),
        )
        assertTrue(
            "the published audit records the verification, not the key",
            script.contains("'apiKeyFingerprintVerified=true'"),
        )
        val auditSection = script.substringAfter("\$googleAuditLines = @(").substringBefore("\n        )")
        assertTrue(
            "the published Google audit must not carry the packaged key or its digest",
            !auditSection.contains("packagedKeyFingerprint") &&
                !auditSection.contains("googleKeyShaped"),
        )
    }

    @Test
    fun `the Google APK is pinned to the certificate its key is restricted to`() {
        val script = releaseScript()

        // The published key's only protection is its Android restriction to this package and this
        // certificate. A Google APK signed by anything else ships a key that cannot authorize, so
        // the signer is checked against the same pinned constant the OpenFreeMap APK uses, and that
        // constant is written once.
        val pinnedDigestAssignments = script.lineSequence()
            .filter { line -> line.contains("\$expectedCertificateDigest =") }
            .toList()
        assertEquals(
            "the pinned certificate digest must be written exactly once",
            1,
            pinnedDigestAssignments.size,
        )
        assertTrue(
            script.contains("is not the pinned TrailVeil certificate"),
        )
        assertTrue(
            "the Google APK must report one signer",
            script.contains("The Google APK must report exactly one signer certificate digest."),
        )
        assertTrue(
            "the Google APK's identity must be derived from the release APK's, not repeated",
            script.contains("\$expectedGoogleFacts.versionName = \"\$(\$expectedFacts.versionName)-google\""),
        )
        assertTrue(
            "the Google APK must not be debuggable, by the same expectation the release APK uses",
            script.contains("foreach (\$key in \$expectedFacts.Keys)"),
        )
    }

    @Test
    fun `every published APK is re-hashed where it will be uploaded from`() {
        val script = releaseScript()

        assertTrue(
            "each staged APK's digest must be re-checked after the copy",
            script.contains("foreach (\$publishedName in \$publishedDigests.Keys)"),
        )
        assertTrue(
            "the ready marker must name the Google artifact and its digest",
            script.contains("googleArtifact=\$googleArtifactName") &&
                script.contains("googleSha256=\$googleHash"),
        )
        // The publishable directory holds exactly what was audited and nothing else, so a stray
        // file cannot ride along with the upload.
        assertTrue(script.contains("audited public files"))
    }

    private fun releaseScript(): String {
        val script = File(repositoryRoot(), "scripts/build-github-release.ps1")
        assertTrue("release script not found at ${script.path}", script.isFile)
        return script.readText()
    }

    private fun repositoryRoot(): File {
        val moduleDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return requireNotNull(moduleDirectory.parentFile)
    }
}
